"""Offline planner evaluation (SPEC 11).

Runs every labelled snapshot in `eval/snapshots/` through the same Planner code path as `/v1/plan`
and reports step accuracy, action accuracy, invalid-output rate and latency p50/p90, plus a CSV of
the failures.

    cd backend
    python -m eval.eval_planner                 # uses LLM_MODEL / GEMINI_API_KEY from .env
    python -m eval.eval_planner --mock          # keyword mock, no API key (checks the harness only)

A snapshot file is what the app's "Save snapshot" writes, with `goal_text` and `expected` filled in:
    {"goal_text": "WiFi on karna hai", "expected": [{"action": "tap", "element_id": 4}],
     "history": [], "screen": {...}}
`expected` lists every acceptable answer; omit `element_id` to accept any element for that action.
"""

import argparse
import asyncio
import csv
import json
import math
import sys
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

from app.config import get_settings  # noqa: E402
from app.llm.mock import MockProvider  # noqa: E402
from app.main import build_provider  # noqa: E402
from app.planner import Planner, PlannerUnavailable  # noqa: E402
from app.schemas import PlanRequest  # noqa: E402

DEFAULT_SNAPSHOTS = BACKEND_DIR / "eval" / "snapshots"
DEFAULT_REPORTS = BACKEND_DIR / "eval" / "reports"


@dataclass
class Case:
    name: str
    request: PlanRequest
    expected: list[dict]


@dataclass
class Outcome:
    case: Case
    action: str | None = None
    element_id: int | None = None
    say_en: str = ""
    confidence: float | None = None
    latency_ms: int | None = None
    valid: bool = False
    retried: bool = False
    error: str | None = None
    step_ok: bool = False
    action_ok: bool = False


@dataclass
class Report:
    outcomes: list[Outcome] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)

    @property
    def scored(self) -> list[Outcome]:
        return [o for o in self.outcomes if o.error is None]

    def rate(self, pred) -> float | None:
        s = self.scored
        return sum(1 for o in s if pred(o)) / len(s) if s else None


def load_cases(folder: Path) -> tuple[list[Case], list[str]]:
    cases, skipped = [], []
    for path in sorted(folder.glob("*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            skipped.append(f"{path.name}: not JSON ({e})")
            continue
        goal = (data.get("goal_text") or "").strip()
        expected = data.get("expected") or []
        if not goal or not expected:
            skipped.append(f"{path.name}: not labelled (goal_text/expected empty)")
            continue
        history = data.get("history") or []
        try:
            request = PlanRequest.model_validate({
                "session_id": f"eval-{path.stem}",
                "goal_text": goal,
                "step_index": data.get("step_index") or len(history) + 1,
                "history": history,
                "screen": data["screen"],
            })
        except Exception as e:  # pydantic ValidationError or missing screen
            skipped.append(f"{path.name}: invalid ({str(e).splitlines()[0]})")
            continue
        cases.append(Case(path.stem, request, expected))
    return cases, skipped


def matches(expected: list[dict], action: str | None, element_id: int | None) -> tuple[bool, bool]:
    step_ok = any(
        e.get("action") == action and (e.get("element_id") is None or e.get("element_id") == element_id)
        for e in expected
    )
    action_ok = any(e.get("action") == action for e in expected)
    return step_ok, action_ok


async def run_case(planner: Planner, case: Case, sem: asyncio.Semaphore) -> Outcome:
    async with sem:
        out = Outcome(case)
        try:
            resp, out.valid, out.retried = await planner.plan_detailed(case.request)
        except PlannerUnavailable as e:
            out.error = str(e)
            return out
        out.action, out.element_id = resp.action, resp.element_id
        out.say_en, out.confidence, out.latency_ms = resp.say_en, resp.confidence, resp.latency_ms
        out.step_ok, out.action_ok = matches(case.expected, resp.action, resp.element_id)
        return out


def percentile(values: list[int], p: float) -> int | None:
    if not values:
        return None
    s = sorted(values)
    k = max(0, min(len(s) - 1, math.ceil(p / 100 * len(s)) - 1))
    return s[k]


def write_failures(report: Report, out_dir: Path, stamp: str) -> Path | None:
    bad = [o for o in report.outcomes if not o.step_ok]
    if not bad:
        return None
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"failures-{stamp}.csv"
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["snapshot", "package", "goal_text", "expected", "got_action", "got_element_id",
                    "say_en", "confidence", "valid", "retried", "latency_ms", "error"])
        for o in bad:
            r = o.case.request
            w.writerow([o.case.name, r.screen.package, r.goal_text, json.dumps(o.case.expected), o.action,
                        o.element_id, o.say_en, o.confidence, o.valid, o.retried, o.latency_ms, o.error or ""])
    return path


def summarize(report: Report) -> dict:
    lat = [o.latency_ms for o in report.scored if o.latency_ms is not None]
    by_pkg: dict[str, list[Outcome]] = {}
    for o in report.scored:
        by_pkg.setdefault(o.case.request.screen.package, []).append(o)
    return {
        "snapshots": len(report.outcomes),
        "skipped": len(report.skipped),
        "errors": sum(1 for o in report.outcomes if o.error),
        "step_accuracy": report.rate(lambda o: o.step_ok),
        "action_accuracy": report.rate(lambda o: o.action_ok),
        "invalid_rate": report.rate(lambda o: not o.valid),
        "retry_rate": report.rate(lambda o: o.retried),
        "latency_p50_ms": percentile(lat, 50),
        "latency_p90_ms": percentile(lat, 90),
        "by_package": {
            pkg: {"n": len(v), "step_accuracy": sum(o.step_ok for o in v) / len(v)} for pkg, v in sorted(by_pkg.items())
        },
    }


def print_summary(s: dict, report: Report, failures: Path | None) -> None:
    pct = lambda x: "—" if x is None else f"{x * 100:.1f}%"  # noqa: E731
    print(f"Snapshots evaluated: {s['snapshots']}  (skipped {s['skipped']}, errors {s['errors']})")
    print(f"Step accuracy:   {pct(s['step_accuracy'])}")
    print(f"Action accuracy: {pct(s['action_accuracy'])}")
    print(f"Invalid output:  {pct(s['invalid_rate'])}   (needed a retry: {pct(s['retry_rate'])})")
    print(f"Latency p50 / p90: {s['latency_p50_ms']} / {s['latency_p90_ms']} ms")
    for pkg, v in s["by_package"].items():
        print(f"  {pkg}: {v['n']} snapshots, step accuracy {pct(v['step_accuracy'])}")
    for line in report.skipped:
        print(f"  skipped {line}")
    if failures:
        print(f"Failures written to {failures}")


async def evaluate(snapshots: Path, mock: bool, concurrency: int) -> Report:
    settings = get_settings()
    provider = MockProvider() if mock else build_provider(settings)
    planner = Planner(provider, log=None)
    cases, skipped = load_cases(snapshots)
    sem = asyncio.Semaphore(max(1, concurrency))
    outcomes = await asyncio.gather(*(run_case(planner, c, sem) for c in cases))
    return Report(list(outcomes), skipped)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--snapshots", type=Path, default=DEFAULT_SNAPSHOTS)
    ap.add_argument("--out", type=Path, default=DEFAULT_REPORTS)
    ap.add_argument("--mock", action="store_true", help="use the keyword mock planner (no API key)")
    ap.add_argument("--concurrency", type=int, default=3)
    ap.add_argument("--json", action="store_true", help="also print the summary as JSON")
    args = ap.parse_args(argv)

    report = asyncio.run(evaluate(args.snapshots, args.mock, args.concurrency))
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    failures = write_failures(report, args.out, stamp)
    summary = summarize(report)
    print_summary(summary, report, failures)
    if args.json:
        print(json.dumps(summary, indent=2))
    return 0 if report.outcomes else 1


if __name__ == "__main__":
    sys.exit(main())
