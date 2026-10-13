# Rehnuma build decisions

Decisions made while building from `docs/SPEC.md`. Newest phase at the bottom. Open questions from SPEC 15 that need the demo phone are tracked at the end.

## Phase 0: Skeleton (2026-10-14)

- **Repository root** is `rehnuma/` with `app/` (Flutter), `backend/` (FastAPI), `tools/`, `docs/`, matching SPEC 3.2.
- **Toolchain** (whatever this PC had): Flutter 3.44.0 stable / Dart 3.12, AGP 9.0.1, Kotlin 2.3.20, Gradle 9.1, JDK 17. Python 3.13 in `backend/.venv`.
- **Android ids**: Dart package `rehnuma`, Android `namespace`/`applicationId` `pk.rehnuma.app`, `minSdk 26`, `targetSdk 35`.
- **Kotlin libraries**: `kotlinx-serialization-json 1.9.0`, `kotlinx-coroutines-android 1.10.2`, `okhttp 4.12.0`, `androidx.core 1.16.0`. JVM unit tests use JUnit 4.
- **Cleartext HTTP is allowed** (`usesCleartextTraffic="true"`). SPEC 6.9 runs the backend on a laptop on the same Wi-Fi (`http://192.168.x.x:8000`), which has no TLS. Use an HTTPS tunnel or cloud VM URL in Settings when available.
- **Default backend URL** is `http://10.0.2.2:8000` (the host PC as seen from the Android emulator). On the demo phone set it in Settings.
- **Extra channel methods** beyond SPEC 10.1: `getPermissions`, `requestPermission` (avoids a Flutter permissions plugin), `isBubbleVisible`, `getState`, `saveSnapshot {delayMs}`. `onboarded` is stored with the other settings.
- **Notification**: an ongoing notification posted by the accessibility service (no foreground service is needed because the accessibility service is already bound by the system). Its "Band karein" action is a package-private broadcast.
- **Share sheet**: exports use a `FileProvider` (`${applicationId}.files`). Opening the system share sheet from an explicit button press is not "launching another app" in the sense of rule 1, and SPEC 5.14/11 require it.
- **`tools/check_no_actions.sh`** scans `*.kt`, `*.kts`, `*.dart`, `*.java` under `app/` (build output excluded) for the six forbidden names, and checks that the service XML has no `android:canPerformGestures` / `android:canTakeScreenshot` attribute. Comments that mention the forbidden names also fail the check on purpose: keep them out of the sources.
- **Urdu font**: the Noto Nastaliq Urdu font file is not bundled yet; Urdu text uses Android's default Arabic-script font until it is added in Phase 8.
- **Backend** uses a `create_app(settings)` factory so tests inject settings. `GET /v1/ping` (authenticated no-op) was added so the app can test the key.

## Phase 1: See the screen (2026-10-14)

- **Masking happens when the screen is read**, not just before upload: `ScreenReader.clean()` masks every text, description, hint, derived label and window title, and `toApi()` masks again before upload. Nothing downstream (planner, cache, debug files) ever sees the raw long numbers.
- **Masking regex** is SPEC 5.3's with the optional separator moved *between* digits: `(?<!\d)\d(?:[\s-]?\d){5,}(?!\d)`. The SPEC version also swallows one trailing space or dash ("Call 03001234567 now" became "Call [number]now"). Same digits are masked.
- **Derived labels only for unlabeled clickables** (SPEC 5.2). Their first three descendant texts become the label, and those texts are not repeated as separate elements ("no kept ancestor already uses the same label" is read as "the text is already part of an ancestor's label"). Scrollable lists and inputs never borrow their children's text, so section titles stay visible.
- `checkable` nodes count as clickable; `selected` is only sent when true; class names are simplified to Button, ImageButton, EditText, TextView, ImageView, CheckBox, RadioButton, Switch, ToggleButton, SeekBar, Spinner, WebView, List (scrollers) or View.
- Element bounds are clipped to the screen, so pointing at a half-visible row aims at its visible part.
- **SafetyGuard interpretations** (all unit-tested in `SafetyGuardTest`):
  - (b) for inputs, `viewId` and `hint` are always checked; `text`/`desc` only when 30 characters or less, so a typed chat message containing "password" does not trigger. View ids are split on `_`, `-`, `.` and camelCase first (`et_mpin`, `etPassword`) so `\b` word boundaries work.
  - (c) "starts within 300 px below" means `input.top` is between the label's top and its bottom + 300 px.
  - (d) needs clickable keys covering **all ten digits 0–9**. Counting ten elements would flag calendars with two months of single-digit days.
- **Screen signature** (SPEC 9.2) folds digit runs to `#` before hashing, so a changing clock or unread count does not break PlanCache hits.
- **Debug Boxes and rule 4**: while the developer turns on the Debug Boxes overlay, `onAccessibilityEvent` refreshes the boxes (debounced 300 ms). This counts as a visible, user-started debug session; nothing is logged or uploaded. With the overlay off and no guidance session, events return immediately.
- **Debug snapshot file** uses the planner's request format so `eval_planner.py` can replay it unchanged: `{goal_text, expected, history, screen: {package, window_title, width, height, elements}, captured_at_ms, read_ms}`. `expected` is a list of acceptable answers such as `[{"action": "tap", "element_id": 13}]`, or `[{"action": "home"}]`. Sensitive screens are refused, not saved. The save waits 5 s so you can switch from Rehnuma to the target app.
- **Emulator check (Pixel 10 Pro image, API 37)**: Debug Boxes align with Android Settings rows at offset (0,0); warm reads take 4–13 ms (the first cold read took 827 ms). Real-phone A2 checks on WhatsApp and the wallet app are still to do.

## Phase 2: Point (2026-10-14)

- **Bubble gestures vs talk mode.** SPEC 5.8 asks for both "hold ≥ 250 ms = talk" and "long-press 1.5 s while idle = quick-task panel", which collide in Hold mode. The bubble only reports raw gestures (hold start/end, tap, 1.5 s long-press, ×); the session maps them:
  - *Hold mode* (default): hold = talk; **short tap while idle opens the panel**; short tap during a session repeats the last instruction.
  - *Tap mode*: tap = start/stop listening; 1.5 s long-press while idle opens the panel.
- The bubble window is 80 dp (64 dp circle plus room for the × chip on the side facing the screen centre). Its side and height are remembered between sessions.
- **Hand pointer** is drawn in code (rounded shapes with a dark outline and a yellow cuff) so its anchor is exactly the index fingertip. It mirrors horizontally near the right edge and flips upside down near the bottom so it stays on screen. The ring is clamped inside the screen so full-width rows show all four sides.
- **Scroll direction** means "show what is further that way": for `down` the finger swipes upward across the list. The planner prompt uses the same meaning.
- **Back/Home** show a yellow arrow (up for home, left for back) above the navigation area with the hand tapping the bottom centre; the planner's caption explains gesture vs button navigation.
- **Caption card**: dark rounded card with a yellow border, 20 sp (follows the system font scale), max 2 lines, on the opposite half from the target; at the bottom when there is no target. Urdu-script captions use `res/font/noto_nastaliq_urdu.ttf` if it is added (looked up by name at runtime), otherwise the system Arabic-script font with the `ur-PK` text locale. The emulator image has no system Nastaliq font.
- **Quick panel** is a focusable full-screen overlay with a dimmed scrim, anchored to the top because accessibility overlays are not resized for the keyboard. Typing `#12` points at element 12 (manual test mode).
- **Testing note:** `adb shell uiautomator dump` (and anything else using UiAutomation) makes Android suppress every other accessibility service while it runs. Rehnuma is unbound, its overlays disappear, and it rebinds a second later. Don't use UI dumps while testing overlays; use screenshots.
- **Emulator check:** bubble stays on top of Settings; `#13` points exactly at "Display size & text"; tapping through the pointer overlay opens that screen (A1 touch pass-through).

## Phase 3: Plan (2026-10-14)

- **Models** (checked against Google's model list on 2026-10-14): `LLM_MODEL=gemini-3.8-flash` (current stable Flash) with `LLM_THINKING=low` for latency; `STT_MODEL=gemini-3.8-flash` (accepts audio). Gemini 2.5 models are now restricted to existing users. If planner p50 is above 2 s on the venue network, try `gemini-3.5-flash-lite`. TTS: Cloud Text-to-Speech `gemini-2.5-flash-tts`, voice `Kore`, `ur-PK` (Urdu is a *preview* language for Gemini-TTS; the 3.8 TTS models are not offered through Cloud TTS).
- **Provider interface** takes a list of turns, `plan(system, turns, schema)`, instead of one user string, so the two few-shot examples and the single corrective retry are real conversation turns (SPEC 6.5 deviation).
- **Validation** (SPEC 6.4 step 5): action in the enum; `element_id` required and on screen for tap/type/scroll, forced to null otherwise; `scroll_direction` required for scroll; `say_roman` 1–12 words, `say_urdu` 1–16 whitespace tokens (Urdu script often writes one word with inner spaces, e.g. "واٹس ایپ"); no ids/"element"/"node" in spoken text. `goal_key` is slugified, confidence clamped and reason cut to 25 words rather than rejected.
- **Retry budget**: one LLM call times out at 6.5 s; the corrective retry only runs if at least 2 s of a 7.5 s budget remain, so the phone's 8 s read timeout is not exceeded. If the LLM is unreachable the API returns **503** (the phone retries once, then says NETWORK_SLOW). Two invalid answers return `cant_help` with HTTP 200.
- **Masking on the server** covers the goal text, history and window title as well as element strings.
- **Mock planner** (`LLM_PROVIDER=mock`) is a keyword matcher for development and offline rehearsal only; `/v1/health` reports `"model": "mock"` so it can't be mistaken for the real one.
- **PlannerClient** retries exactly once (OkHttp's own connection retry is off) on timeout, connection failure or 5xx, never on 401/4xx. Cancelling the session cancels the HTTP call. Uploads leave out default values (false flags) to keep requests small.
- **Emulator check**: with the mock backend on the host (`http://10.0.2.2:8000`), typing "YouTube dekhna hai" on the home screen put the hand on the YouTube icon; the phone measured 849 ms for the call. Real Gemini accuracy and latency still need a `GEMINI_API_KEY`.

## Open questions (SPEC 15), to answer on the demo phone

1. Wallet app for the PIN demo, and whether it exposes labels: _pending_
2. `SpeechRecognizer` `ur-PK` quality on the demo phone: _pending_
3. On-device Urdu TTS voice installed: _pending_
4. Measured planner latency over a venue-like network: _pending_
5. Where the backend runs during the demo: _pending_
