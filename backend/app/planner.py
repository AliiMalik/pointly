"""Chooses the next step for `/v1/plan` (SPEC 6.4). Also used as-is by `eval/eval_planner.py`."""

import asyncio
import json
import re
import time

from pydantic import ValidationError

from . import masking, prompts
from .llm.base import LLMError, LLMProvider, Turn
from .logs import JsonlLog
from .schemas import ELEMENT_ACTIONS, LLMPlan, PlanRequest, PlanResponse

MAX_ROMAN_WORDS = 12
# Urdu script often puts spaces inside one spoken word (e.g. "واٹس ایپ"), so it gets more room.
MAX_URDU_WORDS = 16
MAX_REASON_WORDS = 25
TECH_WORDS = re.compile(r"#\d|\belement\b|\bnode\b|\bid\b", re.IGNORECASE)

CANT_HELP_URDU = "اس کام میں میں ابھی مدد نہیں کر سکتی۔"
CANT_HELP_ROMAN = "Is kaam mein main abhi madad nahi kar sakti."


class PlannerUnavailable(Exception):
    """The LLM could not be reached in time. The API answers 503 so the phone retries."""


def mask_request(req: PlanRequest) -> PlanRequest:
    """Re-applies long-number masking to every string from the phone (SPEC 6.4 step 2)."""
    data = req.model_dump()
    data["goal_text"] = masking.mask(data["goal_text"])
    for h in data["history"]:
        h["instruction_roman"] = masking.mask(h["instruction_roman"])
        h["target_label"] = masking.mask(h["target_label"])
    data["screen"]["window_title"] = masking.mask(data["screen"]["window_title"])
    for e in data["screen"]["elements"]:
        for key in ("text", "desc", "hint", "label", "view_id"):
            e[key] = masking.mask(e[key])
    return PlanRequest.model_validate(data)


def _slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", s.lower()).strip("_")[:48] or "unknown_task"


def validate_plan(raw: dict, ids: set[int]) -> tuple[LLMPlan | None, str | None]:
    """Returns (plan, None) when usable, else (None, error message for the retry turn)."""
    try:
        plan = LLMPlan.model_validate(raw)
    except ValidationError as e:
        first = e.errors()[0]
        where = ".".join(str(p) for p in first["loc"])
        return None, f"{where}: {first['msg']}"

    if plan.action in ELEMENT_ACTIONS:
        if plan.element_id is None:
            return None, f'element_id is required for action "{plan.action}"'
        if plan.element_id not in ids:
            return None, f"element_id {plan.element_id} is not on the screen; use one of the listed ids"
    else:
        plan.element_id = None
    if plan.action == "scroll":
        if plan.scroll_direction is None:
            return None, 'scroll_direction is required for "scroll" (up, down, left or right)'
    else:
        plan.scroll_direction = None

    for field, text, limit in (
        ("say_roman", plan.say_roman, MAX_ROMAN_WORDS),
        ("say_urdu", plan.say_urdu, MAX_URDU_WORDS),
    ):
        words = text.split()
        if not words:
            return None, f"{field} must not be empty"
        if len(words) > limit:
            return None, f"{field} has {len(words)} words; use at most {MAX_ROMAN_WORDS}"
        if TECH_WORDS.search(text):
            return None, f"{field} must not mention ids, elements or nodes"

    plan.say_roman = plan.say_roman.strip()
    plan.say_urdu = plan.say_urdu.strip()
    plan.goal_key = _slug(plan.goal_key)
    plan.confidence = min(1.0, max(0.0, plan.confidence))
    plan.reason = " ".join(plan.reason.split()[:MAX_REASON_WORDS])
    return plan, None


def cant_help(goal_key: str = "unknown_task") -> LLMPlan:
    return LLMPlan(
        action="cant_help", element_id=None, scroll_direction=None,
        say_urdu=CANT_HELP_URDU, say_roman=CANT_HELP_ROMAN,
        goal_key=goal_key, confidence=0.0, reason="Planner output was invalid twice.",
    )


class Planner:
    def __init__(
        self,
        provider: LLMProvider,
        log: JsonlLog | None = None,
        log_screens: bool = False,
        call_timeout_s: float = 6.5,
        total_budget_s: float = 7.5,
        min_retry_s: float = 2.0,
    ):
        self.provider = provider
        self.log = log
        self.log_screens = log_screens
        self.call_timeout_s = call_timeout_s
        self.total_budget_s = total_budget_s
        self.min_retry_s = min_retry_s

    async def _call(self, turns: list[Turn], timeout: float) -> tuple[dict | None, str | None, str | None]:
        """Returns (raw dict, raw text for the retry turn, error). Raises LLMError on provider failure."""
        try:
            raw = await asyncio.wait_for(self.provider.plan(prompts.SYSTEM, turns, LLMPlan), timeout=timeout)
        except asyncio.TimeoutError as e:
            raise LLMError("planner call timed out") from e
        except ValueError as e:  # not JSON
            return None, None, str(e)
        return raw, json.dumps(raw, ensure_ascii=False), None

    async def plan(self, request: PlanRequest) -> PlanResponse:
        response, _valid, _retried = await self.plan_detailed(request)
        return response

    async def plan_detailed(self, request: PlanRequest) -> tuple[PlanResponse, bool, bool]:
        """Like `plan`, plus whether the model's answer was valid and whether a retry was needed."""
        start = time.monotonic()
        req = mask_request(request)
        ids = {e.id for e in req.screen.elements}
        turns = [t for user, model in prompts.FEW_SHOT for t in (Turn("user", user), Turn("model", model))]
        turns.append(Turn("user", prompts.user_message(req)))

        retried = False
        plan: LLMPlan | None = None
        try:
            raw, raw_text, error = await self._call(turns, self.call_timeout_s)
            if raw is not None:
                plan, error = validate_plan(raw, ids)
            remaining = self.total_budget_s - (time.monotonic() - start)
            if plan is None and remaining >= self.min_retry_s:
                retried = True
                turns += [Turn("model", raw_text or "{}"), Turn("user", prompts.correction_message(error or "invalid"))]
                try:
                    raw, _, error = await self._call(turns, min(self.call_timeout_s, remaining))
                    if raw is not None:
                        plan, error = validate_plan(raw, ids)
                except LLMError:
                    plan = None  # first answer was already invalid: give up politely
        except LLMError as e:
            latency_ms = int((time.monotonic() - start) * 1000)
            self._log(req, None, latency_ms, retried, valid=False, error=str(e))
            raise PlannerUnavailable(str(e)) from e

        valid = plan is not None
        if plan is None:
            plan = cant_help()
        latency_ms = int((time.monotonic() - start) * 1000)
        self._log(req, plan, latency_ms, retried, valid=valid)
        return PlanResponse(**plan.model_dump(), latency_ms=latency_ms), valid, retried

    def _log(self, req: PlanRequest, plan: LLMPlan | None, latency_ms: int, retried: bool, valid: bool, error: str | None = None) -> None:
        if self.log is None:
            return
        record = {
            "session_id": req.session_id,
            "step_index": req.step_index,
            "package": req.screen.package,
            "n_elements": len(req.screen.elements),
            "goal_text": req.goal_text,
            "action": plan.action if plan else None,
            "element_id": plan.element_id if plan else None,
            "goal_key": plan.goal_key if plan else None,
            "confidence": plan.confidence if plan else None,
            "latency_ms": latency_ms,
            "retried": retried,
            "valid": valid,
            "history_outcomes": [h.outcome for h in req.history],
        }
        if error:
            record["error"] = error
        if self.log_screens:
            record["elements"] = [e.model_dump(exclude_none=True) for e in req.screen.elements]
        self.log.write(record)
