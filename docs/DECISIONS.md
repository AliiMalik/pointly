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

## Open questions (SPEC 15), to answer on the demo phone

1. Wallet app for the PIN demo, and whether it exposes labels: _pending_
2. `SpeechRecognizer` `ur-PK` quality on the demo phone: _pending_
3. On-device Urdu TTS voice installed: _pending_
4. Measured planner latency over a venue-like network: _pending_
5. Where the backend runs during the demo: _pending_
