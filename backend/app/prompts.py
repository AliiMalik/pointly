"""Planner prompt (SPEC 6.6): system text, compact screen format and two few-shot turns."""

import json

from .schemas import Element, HistoryItem, PlanRequest

SYSTEM = """\
You are Rehnuma, a patient guide that helps first-time smartphone users in Pakistan
complete tasks on Android. You NEVER act for the user. You only tell the user which ONE
element on the current screen to press, type into or scroll next.

You receive:
- GOAL: what the user wants, in Urdu or Roman Urdu.
- HISTORY: the steps already guided and whether the user followed them.
- SCREEN: the package name and a numbered list of on-screen elements with labels and positions.

Rules:
1. Choose exactly ONE next action. Use only element ids from SCREEN. Never invent ids.
2. Prefer the most direct path a normal user would take. Prefer visible, clearly labelled elements.
3. If the needed element is not visible but a scrollable list is, choose "scroll" on that list.
   scroll_direction is where the hidden content is: "down" means items further down the list.
3b. If the user must move a slider (shown as "slider 2 of 0-4"), choose "drag" on that slider with
   scroll_direction = the way the finger moves: "right" for more or bigger, "left" for less or smaller.
   Do not choose "tap" for a slider. If HISTORY shows the slider was already moved the right way and
   the goal does not ask for the biggest or smallest, choose the button that saves it (Apply, OK, Save)
   or "done" when there is none.
4. If the goal needs another app and the screen is not the home screen or that app, choose "home".
5. If the goal is unclear (for example which contact or which photo), choose "ask" and ask one short question.
6. If the goal is already achieved on this screen, choose "done".
7. If the next step would be entering a PIN, password, OTP or confirming a payment, choose "done"
   and say that the user should do this part themselves.
8. If the task is impossible from here or unsafe, choose "cant_help".
9. Write say_urdu in simple Urdu script (Arabic letters, e.g. "سبز بٹن دبائیں") and say_roman in simple
   Roman Urdu (English letters only, e.g. "Sabz button dabayein"). Never put Roman letters in say_urdu,
   except app names like WhatsApp. One short sentence,
   at most 12 words, polite form ("dabayein", "likhein"). Describe the element the way a user sees it:
   by its colour, icon or text (for example "hara WhatsApp icon", "neeche wala bheejne ka teer").
10. Never mention element ids, "element", "node" or technical words to the user.
11. Return only JSON that matches the schema.

More details:
- element_id is required for tap, type, scroll and drag, and must be null for the other actions.
- For "type", tell the user what to type; the user types it themselves.
- For "back" or "home", say how: "Neeche peeche wala button dabayein" or "Neeche se upar swipe karein".
- goal_key is a short snake_case English slug for the whole task, the same on every step
  (for example "whatsapp_send_photo", "settings_font_size", "wifi_on", "call_contact", "mobile_load").
- confidence is between 0 and 1. reason is English, at most 25 words, for logs only.
- Text like [number] means a long number was hidden for privacy.
"""

USER_TEMPLATE = """\
GOAL: {goal_text}
STEP: {step_index}
HISTORY:
{history_lines}
SCREEN (package={package}, size={width}x{height}{title}):
{element_lines}"""


def element_line(e: Element) -> str:
    """`#2 [View, clickable] "Sara | Sara · Kal milte hain" @ (0,420)-(1080,560)` (SPEC 6.4 step 3)."""
    flags = [e.cls]
    if e.clickable:
        flags.append("clickable")
    if e.editable:
        flags.append("editable")
    if e.scrollable:
        flags.append("scrollable")
    if e.checked is not None:
        flags.append("checked" if e.checked else "unchecked")
    if e.selected:
        flags.append("selected")
    if e.range:
        lo, hi, cur = e.range
        flags.append(f"slider {cur:g} of {lo:g}-{hi:g}")

    parts: list[str] = []
    for value, prefix in ((e.text, ""), (e.desc, ""), (e.hint, "hint: "), (e.label, ""), (e.view_id, "id: ")):
        if value and f"{prefix}{value}" not in parts:
            parts.append(f"{prefix}{value}")
    label = " | ".join(parts) if parts else "(no label)"
    l, t, r, b = e.bounds
    return f'#{e.id} [{", ".join(flags)}] {json.dumps(label, ensure_ascii=False)} @ ({l},{t})-({r},{b})'


def history_lines(history: list[HistoryItem]) -> str:
    if not history:
        return "none"
    lines = []
    for h in history[-8:]:
        target = f" -> {h.target_label}" if h.target_label else ""
        lines.append(f'{h.step}. "{h.instruction_roman}"{target} [{h.outcome}]')
    return "\n".join(lines)


def user_message(req: PlanRequest) -> str:
    s = req.screen
    return USER_TEMPLATE.format(
        goal_text=req.goal_text,
        step_index=req.step_index,
        history_lines=history_lines(req.history),
        package=s.package,
        width=s.width,
        height=s.height,
        title=f", title={json.dumps(s.window_title, ensure_ascii=False)}" if s.window_title else "",
        element_lines="\n".join(element_line(e) for e in s.elements) or "(empty screen)",
    )


def _req(goal: str, step: int, history: list[dict], package: str, elements: list[dict], title: str | None = None) -> PlanRequest:
    return PlanRequest.model_validate({
        "session_id": "example",
        "goal_text": goal,
        "step_index": step,
        "history": history,
        "screen": {"package": package, "window_title": title, "width": 1080, "height": 2400, "elements": elements},
    })


# Two worked examples sent as earlier conversation turns (SPEC 6.6).
FEW_SHOT: list[tuple[str, str]] = [
    (
        user_message(_req(
            "Beti Sara ko WhatsApp pe photo bhejni hai",
            2,
            [{"step": 1, "instruction_roman": "Yeh hara WhatsApp icon dabayein", "target_label": "WhatsApp", "outcome": "done"}],
            "com.whatsapp",
            [
                {"id": 1, "text": "WhatsApp", "cls": "TextView", "bounds": [40, 120, 400, 200]},
                {"id": 2, "desc": "Camera", "cls": "ImageButton", "clickable": True, "bounds": [780, 110, 880, 210]},
                {"id": 3, "desc": "Search", "cls": "ImageButton", "clickable": True, "bounds": [880, 110, 980, 210]},
                {"id": 4, "hint": "Ask Meta AI or Search", "cls": "EditText", "clickable": True, "editable": True, "bounds": [40, 230, 1040, 330]},
                {"id": 5, "cls": "List", "scrollable": True, "view_id": "conversations", "bounds": [0, 340, 1080, 2100]},
                {"id": 6, "label": "Ali Bhai · Salam, kaise ho? · 10:12", "cls": "View", "clickable": True, "bounds": [0, 360, 1080, 520]},
                {"id": 7, "label": "Sara · Kal milte hain · Yesterday", "cls": "View", "clickable": True, "bounds": [0, 520, 1080, 680]},
                {"id": 8, "label": "Family Group · Ammi: Photo · 08:40", "cls": "View", "clickable": True, "bounds": [0, 680, 1080, 840]},
                {"id": 9, "desc": "New chat", "cls": "ImageButton", "clickable": True, "bounds": [900, 1960, 1040, 2100]},
                {"id": 10, "text": "Chats", "cls": "TextView", "clickable": True, "selected": True, "bounds": [0, 2160, 270, 2300]},
                {"id": 11, "text": "Updates", "cls": "TextView", "clickable": True, "bounds": [270, 2160, 540, 2300]},
            ],
        )),
        json.dumps({
            "action": "tap", "element_id": 7, "scroll_direction": None,
            "say_urdu": "سارہ کے نام پر دبائیں", "say_roman": "Sara ke naam pe dabayein",
            "goal_key": "whatsapp_send_photo", "confidence": 0.94,
            "reason": "Sara's chat is visible in the chat list; opening it is the next step.",
        }, ensure_ascii=False),
    ),
    (
        user_message(_req(
            "Phone ke likhai bari karni hai",
            3,
            [
                {"step": 1, "instruction_roman": "Settings ka gear icon dabayein", "target_label": "Settings", "outcome": "done"},
                {"step": 2, "instruction_roman": "Display wala option dabayein", "target_label": "Display & touch", "outcome": "done"},
            ],
            "com.android.settings",
            [
                {"id": 1, "cls": "List", "scrollable": True, "view_id": "content_parent", "bounds": [0, 110, 1080, 2340]},
                {"id": 2, "desc": "Display & touch", "cls": "View", "view_id": "collapsing_toolbar", "bounds": [0, 110, 1080, 270]},
                {"id": 3, "desc": "Navigate up", "cls": "ImageButton", "clickable": True, "bounds": [40, 130, 180, 250]},
                {"id": 4, "text": "Brightness", "cls": "TextView", "view_id": "title", "bounds": [40, 330, 1040, 380]},
                {"id": 5, "label": "Brightness level · 83%", "cls": "View", "clickable": True, "bounds": [0, 400, 1080, 590]},
                {"id": 6, "text": "Appearance", "cls": "TextView", "view_id": "title", "bounds": [40, 820, 1040, 870]},
                {"id": 7, "label": "Dark theme · Will turn on when Bedtime starts", "cls": "View", "clickable": True, "bounds": [0, 870, 1080, 1000]},
                {"id": 8, "desc": "Dark theme", "cls": "Switch", "clickable": True, "checked": False, "view_id": "switchWidget", "bounds": [880, 890, 1040, 980]},
                {"id": 9, "label": "Display size & text · Font size, display size, bold text, ou", "cls": "View", "clickable": True, "bounds": [0, 1000, 1080, 1130]},
                {"id": 10, "label": "Navigation mode · Gesture navigation", "cls": "View", "clickable": True, "bounds": [0, 1130, 1080, 1260]},
            ],
            title="Display & touch",
        )),
        json.dumps({
            "action": "tap", "element_id": 9, "scroll_direction": None,
            "say_urdu": "ڈسپلے سائز اور ٹیکسٹ والا آپشن دبائیں",
            "say_roman": "Display size and text wala option dabayein",
            "goal_key": "settings_font_size", "confidence": 0.9,
            "reason": "Font size lives under Display size & text on this Settings screen.",
        }, ensure_ascii=False),
    ),
]


def correction_message(error: str) -> str:
    """Extra user turn for the one retry after an invalid answer (SPEC 6.4 step 5)."""
    return (
        f"Your last answer was invalid: {error}\n"
        "Answer again for the same GOAL and SCREEN. Return only JSON that matches the schema."
    )
