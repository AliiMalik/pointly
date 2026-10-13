# Rehnuma: MVP Build Specification

Version 1.0 · 14 October 2026 · Owner: Ali Malik and Laiba Sattar
Target: working Android MVP for a live demo at the 4th International AI Championship (final on 24 October 2026).

> **How to use this file (for Claude Code):** Read the whole file once. Then build in the order of Section 13 (Build Phases), one phase at a time. Finish each phase's "Definition of done" before starting the next. When something in this spec is ambiguous, prefer the simplest option that keeps the Non-negotiable rules (Section 2) true, and write the decision into `docs/DECISIONS.md`.

---

## 1. Product summary

Rehnuma is an Android app that helps first-time and low-literacy smartphone users complete tasks in other apps. The user holds a floating bubble and says in Urdu what they want to do ("Beti ko WhatsApp pe photo bhejni hai"). Rehnuma reads the current screen through Android's Accessibility Service, asks an LLM which single on-screen element the user should press next, then draws an animated hand pointing at that element and speaks a short Urdu instruction. **The user always taps themselves.** Rehnuma detects the tap, waits for the next screen, and guides the next step until the task is done. When the user repeats a task, Rehnuma gives less help ("fading"), so the user actually learns.

Inspiration: heyClicky (Mac AI buddy that points at the button to click). Rehnuma brings that interaction to Android, in Urdu, for people using a smartphone for the first time, and never acts on their behalf.

### 1.1 Users
- Primary: elderly and low-literacy Urdu speakers who use WhatsApp, phone settings and wallet apps.
- Demo audience: competition judges watching a phone mirrored to a laptop.

### 1.2 MVP scope (in)
- Android only (minSdk 26, targetSdk 35).
- Floating bubble with hold-to-talk.
- Urdu speech input, Urdu voice output, Roman Urdu caption on screen.
- Screen reading via accessibility tree (no screenshots).
- LLM planner on a small FastAPI backend.
- Animated pointer overlay over the target element.
- Tap verification and automatic next step.
- Safety guard for PIN/password/OTP screens.
- Help fading for repeated steps.
- Metrics logging and an offline evaluation script.
- Five "quick task" buttons as a backup to voice.

### 1.3 Out of scope (do not build)
- iOS.
- Play Store release.
- Performing any tap, gesture, text entry or app launch on the user's behalf.
- Screenshots, screen recording, MediaProjection or vision models.
- Remote family help, accounts, login, cloud database.
- Punjabi or other languages beyond Urdu and Roman Urdu input.

---

## 2. Non-negotiable rules

These rules override everything else in this spec. Add tests or checks for them where possible.

1. **Never act for the user.** The code must never call `performAction` (click, long click, set text, scroll or any other action), never call `dispatchGesture`, never call `performGlobalAction`, and never launch other apps. The only allowed launch is opening Android's accessibility settings page from Rehnuma's own Permissions screen. The accessibility service XML must **not** declare `android:canPerformGestures`. Add a check script (`tools/check_no_actions.sh`) that fails if any of `performAction`, `dispatchGesture`, `performGlobalAction`, `takeScreenshot`, `MediaProjection` or `getLaunchIntentForPackage` appear in Kotlin or Dart sources.
2. **No screenshots.** Do not use `takeScreenshot`, MediaProjection or any screen capture API. Only the accessibility node tree (text, labels, bounds) is read.
3. **Sensitive screens are never sent anywhere.** If the SafetyGuard (Section 5.4) flags a screen, nothing from that screen leaves the phone, the pointer is hidden, and Rehnuma tells the user to fill it themselves.
4. **Read only during an active session.** `onAccessibilityEvent` must return immediately unless a guidance session is active. No background logging of screen content.
5. **No persistent storage of screen content.** The only exception is a debug snapshot the user explicitly saves from the debug menu (Section 11).
6. **Mask long numbers before sending.** Any digit sequence of 6 or more digits (with optional spaces or dashes) is replaced by `[number]` on the phone before upload, and again on the server.
7. **The user can stop at any time.** The bubble's close control and the persistent notification's "Band karein" action end the session immediately and remove all overlays.

---

## 3. Architecture

```
┌──────────────────────── Android phone ─────────────────────────┐
│  Flutter app (screens)        Kotlin core (inside the app)       │
│  ─ Onboarding                 ─ RehnumaAccessibilityService      │
│  ─ Permissions checklist      ─ ScreenReader + SafetyGuard       │
│  ─ Home + quick tasks   ◄──►  ─ GuidanceSession (state machine)  │
│  ─ Settings                   ─ BubbleOverlay + PointerOverlay   │
│  ─ Metrics + debug            ─ SpeechInput + SpeechOutput       │
│     (MethodChannel /          ─ StepVerifier, LearningStore,     │
│      EventChannel)              PlanCache, MetricsLogger         │
│                               ─ PlannerClient (HTTPS)            │
└──────────────────────────────────────┬───────────────────────────┘
                                       │ JSON over HTTPS
                          ┌────────────▼────────────┐
                          │ FastAPI backend          │
                          │ /v1/plan /v1/stt /v1/tts │
                          │ LLM provider (Gemini)    │
                          │ JSONL logs, eval script  │
                          └──────────────────────────┘
```

### 3.1 Technology decisions
| Area | Choice | Reason |
| --- | --- | --- |
| App screens | Flutter (stable channel), Dart | Team's main skill |
| Core (service, overlay, audio) | Kotlin, native Android Views | Overlays and accessibility APIs are native; a Flutter engine inside the overlay is unnecessary |
| Flutter ↔ Kotlin | `MethodChannel("rehnuma/control")`, `EventChannel("rehnuma/events")` | Small, explicit surface |
| HTTP client (Kotlin) | OkHttp + kotlinx.serialization | Simple, reliable |
| Local storage (Kotlin) | Small JSON files in `filesDir` via kotlinx.serialization | No DB needed for MVP |
| Backend | Python 3.11+, FastAPI, Pydantic v2, `google-genai` SDK | Team knows FastAPI; key stays off the phone |
| LLM | Gemini Flash family, model ID from env var | Fast and cheap; check the current model ID in Google's docs at build time, do not hardcode |
| Speech in | Android `SpeechRecognizer` with `ur-PK` (primary), backend `/v1/stt` (fallback) | Fast on-device path, cloud fallback |
| Speech out | Android `TextToSpeech` with `ur-PK` if a voice exists (primary), backend `/v1/tts` using Google Cloud Gemini-TTS `ur-PK` (fallback) | Gemini-TTS lists Urdu (Pakistan) as a preview language |

### 3.2 Repository layout
```
rehnuma/
├── app/                                  # Flutter project
│   ├── lib/
│   │   ├── main.dart
│   │   ├── channel/rehnuma_channel.dart  # MethodChannel/EventChannel wrapper
│   │   ├── screens/onboarding_screen.dart
│   │   ├── screens/permissions_screen.dart
│   │   ├── screens/home_screen.dart
│   │   ├── screens/settings_screen.dart
│   │   ├── screens/metrics_screen.dart
│   │   └── l10n/strings.dart             # Urdu + English UI strings
│   ├── assets/fonts/NotoNastaliqUrdu-Regular.ttf
│   └── android/app/src/main/
│       ├── AndroidManifest.xml
│       ├── res/xml/rehnuma_accessibility_service.xml
│       ├── res/drawable/ (hand pointer, bubble icons as vector drawables)
│       └── kotlin/pk/rehnuma/app/
│           ├── MainActivity.kt
│           ├── bridge/RehnumaChannel.kt
│           ├── service/RehnumaAccessibilityService.kt
│           ├── screen/ScreenReader.kt
│           ├── screen/ScreenSnapshot.kt          # data classes
│           ├── screen/SafetyGuard.kt
│           ├── screen/Masking.kt
│           ├── session/GuidanceSession.kt        # state machine
│           ├── session/StepVerifier.kt
│           ├── session/SettleDetector.kt
│           ├── session/LearningStore.kt
│           ├── session/PlanCache.kt
│           ├── overlay/BubbleOverlay.kt
│           ├── overlay/PointerOverlay.kt
│           ├── overlay/DebugBoxesOverlay.kt
│           ├── voice/SpeechInput.kt
│           ├── voice/SpeechOutput.kt
│           ├── net/PlannerClient.kt
│           ├── net/ApiModels.kt
│           ├── metrics/MetricsLogger.kt
│           └── config/AppConfig.kt
├── backend/
│   ├── app/main.py
│   ├── app/schemas.py
│   ├── app/planner.py
│   ├── app/prompts.py
│   ├── app/masking.py
│   ├── app/llm/base.py
│   ├── app/llm/gemini.py
│   ├── app/stt.py
│   ├── app/tts.py
│   ├── app/logs.py
│   ├── tests/
│   ├── eval/eval_planner.py
│   ├── eval/snapshots/            # labeled screen JSON files
│   ├── requirements.txt
│   └── .env.example
├── tools/check_no_actions.sh
└── docs/
    ├── SPEC.md                    # this file
    └── DECISIONS.md
```

Package name: `pk.rehnuma.app`.

---

## 4. User flow (happy path)

1. User opens the Rehnuma app once, completes onboarding and permissions, taps **"Rehnuma chalu karein"**. The bubble appears at the screen edge and stays on top of all apps.
2. User goes to any screen (usually the home screen), **presses and holds the bubble** and says: "Beti Sara ko WhatsApp pe photo bhejni hai." Releasing the bubble ends listening.
3. Rehnuma says "Ek second, dekh rahi hoon" while it reads the screen and calls the planner.
4. An animated hand flies from the bubble to the WhatsApp icon, a yellow ring pulses around it, a caption shows "Yeh hara WhatsApp icon dabayein", and the same sentence is spoken in Urdu.
5. User taps the icon. Rehnuma detects the click, hides the pointer, waits for WhatsApp to settle, reads the new screen and points at the next element (Sara's chat).
6. Steps continue: chat → attach (paperclip) → Gallery → photo → send.
7. When the planner returns `done`, Rehnuma says "Shabash! Kaam ho gaya." and the session ends.
8. The third time the user does the same task, Rehnuma only speaks short hints and shows the hand only if the user hesitates (Section 9).

If the user taps the wrong thing, Rehnuma says "Koi baat nahi, ab yahan se chalte hain" and re-plans from the new screen.

If a PIN or password screen appears, Rehnuma hides the pointer and says "Yeh screen aap khud bharein. Main ise nahi dekhti." It resumes when the screen no longer contains sensitive fields.

---

## 5. Kotlin core: components

### 5.1 RehnumaAccessibilityService
- Declare the service in the manifest with `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, `android:exported="true"` (required so the system can bind to it; the permission protects it), an intent-filter for `android.accessibilityservice.AccessibilityService`, and a `meta-data` entry `android.accessibilityservice` pointing to the XML below.
- `res/xml/rehnuma_accessibility_service.xml`:
  ```xml
  <accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
      android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged|typeViewClicked|typeViewTextChanged|typeViewFocused|typeViewScrolled"
      android:accessibilityFeedbackType="feedbackGeneric"
      android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
      android:canRetrieveWindowContent="true"
      android:isAccessibilityTool="true"
      android:notificationTimeout="100"
      android:description="@string/accessibility_service_description" />
  ```
  Do **not** add `canPerformGestures` or `canTakeScreenshot`.
- `accessibility_service_description` (English + Urdu): explains that Rehnuma reads on-screen labels only while the user asks for help, never taps for the user, never takes screenshots, and stops reading on PIN or password screens.
- Holds a static reference (`companion object { var instance: RehnumaAccessibilityService? }`) set in `onServiceConnected`, cleared in `onUnbind`/`onDestroy`, so `MainActivity` can call it.
- Owns: `GuidanceSession`, `BubbleOverlay`, `PointerOverlay`, `DebugBoxesOverlay`, `SpeechInput`, `SpeechOutput`.
- `onAccessibilityEvent(event)`: if no active session → return immediately. Otherwise forward to `GuidanceSession.onEvent(event)`.
- Shows a persistent foreground-style notification while the bubble is visible, with action **"Band karein"** that stops the session and hides the bubble. (Request `POST_NOTIFICATIONS` on Android 13+ from the Flutter app.)

### 5.2 ScreenReader
Produces a `ScreenSnapshot` of the current app window.

Window selection:
1. Iterate `windows` (available because of `flagRetrieveInteractiveWindows`).
2. Ignore windows whose root package is `pk.rehnuma.app` (our own overlays) and windows of type `TYPE_INPUT_METHOD` (keyboard) and `TYPE_ACCESSIBILITY_OVERLAY`.
3. Prefer the focused/active `TYPE_APPLICATION` window. If a `TYPE_SYSTEM` window (for example a permission dialog) is on top and active, use it.
4. Fallback: `rootInActiveWindow`.

Traversal (depth-first, max depth 40, max visited nodes 1500):
- Skip nodes where `isVisibleToUser == false` or bounds are empty or fully outside the screen.
- Keep a node as an **element** if any of these is true: `isClickable`, `isLongClickable`, `isEditable`, `isCheckable`, `isScrollable`, or it has non-blank `text`/`contentDescription` and no kept ancestor already uses the same label.
- Label for a clickable node with no own text/description: join the first 3 non-blank descendant texts with " · ", max 60 chars.
- Cap at **80 elements**. If more, keep interactive elements first, then text elements nearest the top.
- Sort by reading order: by `top` (bucketed to 24 px rows), then by `left`.
- Assign `id` 1..N in that order.

`ScreenElement` fields:
```kotlin
@Serializable data class ScreenElement(
  val id: Int,
  val text: String? = null,         // node.text, trimmed, max 80 chars
  val desc: String? = null,         // contentDescription, max 80 chars
  val hint: String? = null,         // hintText (API 26+)
  val label: String? = null,        // derived label for unlabeled clickables
  val viewId: String? = null,       // short id after ":id/"
  val cls: String,                  // simplified class: Button, ImageButton, EditText, TextView, ImageView, CheckBox, Switch, View, ...
  val clickable: Boolean = false,
  val editable: Boolean = false,
  val scrollable: Boolean = false,
  val checked: Boolean? = null,
  val selected: Boolean? = null,
  val bounds: List<Int>             // [left, top, right, bottom] in screen pixels
)
```
`ScreenSnapshot` fields: `packageName`, `windowTitle` (if any), `screenWidth`, `screenHeight`, `elements`, `signature` (Section 9.2), `capturedAtMs`, `readMs` (time taken).

Recycle node references properly (or use the non-recycling APIs on newer SDKs). Do not keep `AccessibilityNodeInfo` objects beyond one read; keep only `ScreenElement` data and a `TargetSignature` for verification.

Performance target: `readMs` under 150 ms on a mid-range phone for typical screens. Log it.

### 5.3 Masking (phone side, also repeated on server)
- Regex: `(?<!\d)(?:\d[\s-]?){6,}(?!\d)` → `[number]`.
- Apply to `text`, `desc`, `hint`, `label` before any upload.
- Unit-test with CNIC-like strings (`35202-1234567-1`), phone numbers (`0300 1234567`), and short numbers that must stay (`Rs 500`, `12:45`).

### 5.4 SafetyGuard
`fun check(snapshot): GuardResult` returns `SAFE` or `SENSITIVE(reason)`.

Sensitive-word pattern `SENSITIVE_RE` (case-insensitive):
`\b(pin|mpin|m-pin|password|passcode|otp|cvv|cvc|card number|security code)\b|(?<!\p{L})(پن|پن کوڈ|پاس ورڈ|خفیہ کوڈ)(?!\p{L})`
(The Urdu words are matched as whole words only, so common words like "اپنا" do not trigger.)

A screen is **SENSITIVE** if any of these is true:
- (a) any node has `isPassword == true` (check this during traversal and carry a flag on the snapshot);
- (b) an **editable** element's `viewId`, `hint`, `text` or `desc` matches `SENSITIVE_RE`;
- (c) a non-editable element with short text (30 characters or less) matches `SENSITIVE_RE` **and** an editable element starts within 300 px below it (a label above an input field);
- (d) the screen shows a **custom PIN keypad**: 10 or more clickable elements whose labels are the single digits 0 to 9 (wallet apps often use their own keypad instead of a text field).

A long chat message that contains the word "password" must not trigger rule (b) or (c). Unit-test all four rules, including the "اپنا" and chat-message negatives.

When SENSITIVE:
- Do not call the planner, do not upload anything, hide the pointer, keep the bubble in a "paused" state (lock icon).
- Speak and caption: `SENSITIVE_PAUSE` phrase (Section 8.3).
- Keep listening to window events; when a new snapshot is SAFE, resume the session automatically.
- Log only `{event: "sensitive_pause", package}` (no content).

### 5.5 GuidanceSession (state machine)
States:
```
IDLE
LISTENING            // bubble held, recording speech
TRANSCRIBING         // waiting for STT result (fallback path)
READING_SCREEN       // waiting for screen to settle, then ScreenReader
SENSITIVE_PAUSE
PLANNING             // waiting for /v1/plan or plan cache
POINTING             // pointer + caption visible, TTS speaking
WAITING_FOR_USER     // expecting the user's tap/typing/scroll
ASKING               // planner asked a clarifying question; waiting for user's spoken answer
COMPLETED
ERROR                // recoverable; offer retry
```
Transitions (main ones):
- `IDLE → LISTENING` on bubble press-and-hold (or quick-task tap → directly `READING_SCREEN` with the quick-task goal text).
- `LISTENING → READING_SCREEN` when STT returns non-blank text; store as `goalText`. If blank or error: speak `DIDNT_HEAR` and go back to `IDLE` (or previous state if a session is active).
- `READING_SCREEN → SENSITIVE_PAUSE` if guard says SENSITIVE; else `→ PLANNING`.
- `PLANNING → POINTING` on a valid plan with a target; `→ ASKING` on `ask`; `→ COMPLETED` on `done`; `→ ERROR` on `cant_help` (speak `CANT_HELP`, then IDLE) or network failure after retry.
- `POINTING → WAITING_FOR_USER` when the pointer animation finishes (TTS can keep playing).
- `WAITING_FOR_USER → READING_SCREEN` when StepVerifier reports success, or when the screen changes in a way that does not match the target (deviation: speak `DEVIATION` once, mark the step as deviated in history).
- `ASKING → PLANNING` when the user answers by holding the bubble again (append answer to `goalText` as `"\nUser answer: ..."`).
- Any state `→ IDLE` on cancel (bubble close, notification action), removing all overlays and stopping TTS/STT.

Limits: max 25 steps per session; on reaching it, speak `CANT_HELP` and end. Planner timeout 8 s, one automatic retry, then `NETWORK_SLOW` and `ERROR` with a "Dobara" (retry) option on the bubble.

History kept per session (sent to planner): list of `{step, instruction_roman, target_label, outcome: "done"|"deviated"|"skipped"}`, last 8 entries only.

### 5.6 SettleDetector
After a tap or a window change, wait until no `TYPE_WINDOW_CONTENT_CHANGED`/`TYPE_WINDOW_STATE_CHANGED` events arrive for **500 ms**, with a hard cap of **2500 ms**, then read the screen. Debounce all event bursts with this detector.

### 5.7 StepVerifier
When a plan step points at element E, store a `TargetSignature`: `packageName`, `viewId`, normalized `text/desc/label`, `cls`, `bounds`.

Success rules depend on `action`:
- `tap`: on `TYPE_VIEW_CLICKED`, take `event.source` (may be null). Match if: same package AND (same non-null `viewId` OR same normalized label) OR the source's bounds contain the center of E's bounds. If `event.source` is null, use the next screen change: if the new snapshot no longer contains E or the window changed, count as success.
  Fallback for apps that do not emit `TYPE_VIEW_CLICKED` (some Compose/Flutter/WebView screens): if a `TYPE_WINDOW_STATE_CHANGED` or a large content change happens within 10 s of pointing, treat it as progress and re-read the screen.
- `type`: success when `TYPE_VIEW_TEXT_CHANGED` occurs on the target field and then 3 s pass with no further typing, or focus leaves the field.
- `scroll`: success on `TYPE_VIEW_SCROLLED` on or inside the target container, or any change in the visible element set.
- `back` / `home`: success on window change.
A click that clearly does not match (different element, same screen stays) → speak `WRONG_TAP` once ("Yeh nahi, jahan haath hai wahan dabayein"), keep pointing.

### 5.8 BubbleOverlay
- Separate overlay window: `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS`, size 64 dp, touchable, draggable along screen edges, snaps to the nearest side.
- Appearance: round, high contrast, original design (green circle with the Urdu letter "ر" in white). States: idle, listening (pulsing red ring + mic icon), thinking (three animated dots), speaking (sound waves), paused (lock icon), error (small "↻" retry).
- Gestures:
  - Press and hold ≥ 250 ms → start listening; release → stop and process. (Settings option "Tap to talk": tap starts, second tap stops.)
  - Short tap while a session is active → repeat the last instruction.
  - Small "×" chip next to the bubble while a session is active → cancel session.
  - Long-press 1.5 s while idle → open a small panel with the 5 quick tasks and a Roman Urdu text box (for noisy rooms).

### 5.9 PointerOverlay
- Full-screen overlay window: `TYPE_ACCESSIBILITY_OVERLAY`, flags `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`, `layoutInDisplayCutoutMode = SHORT_EDGES` (API 28+), transparent. It must never block touches: every touch passes to the app below.
- Coordinates: element bounds are screen coordinates. Verify alignment on the demo phone with the Debug Boxes overlay (Section 11); if there is a vertical offset, compute it from the overlay window's location on screen and subtract it.
- Draws:
  1. A pulsing ring (yellow `#FFC107`, 4 dp, with a dark outline for contrast) around the target bounds, inflated by 8 dp.
  2. A hand pointer vector (≥ 64 dp) that animates from the bubble to the target center in 500 ms (ease-out), then taps in place (scale 1.0 → 0.85 → 1.0, repeating every 1.2 s).
  3. For `scroll`: the hand moves in the scroll direction across the target container, repeating.
  4. For `back`/`home`: the hand points at the bottom center navigation area with an arrow; caption explains.
  5. A caption card (rounded, dark background, white text, 20 sp, max 2 lines) placed on the opposite half of the screen from the target. Caption shows Roman Urdu by default; Settings can switch to Urdu script (Noto Nastaliq Urdu).
- Respect system font scale for the caption.
- Hide immediately on success, cancel, sensitive pause or error.

### 5.10 SpeechInput
- Primary: `SpeechRecognizer.createSpeechRecognizer(context)` on the main thread, intent extras: `EXTRA_LANGUAGE = "ur-PK"`, `EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM`, `EXTRA_PARTIAL_RESULTS = true`, `EXTRA_MAX_RESULTS = 3`. Show partial results in the caption while listening.
- If `SpeechRecognizer.isRecognitionAvailable` is false, or Urdu fails with a language error, or Settings selects "Cloud": record audio with `AudioRecord`/`MediaRecorder` (16 kHz mono) while the bubble is held, upload to `/v1/stt`.
- Return `SpeechResult(textUrdu: String?, textRoman: String?, source: "device"|"cloud", ms: Long)`.

### 5.11 SpeechOutput
- Primary: `TextToSpeech` with `Locale("ur", "PK")` if `isLanguageAvailable` returns a supported value and a voice exists.
- Fallback: POST `/v1/tts` with the Urdu-script text, play returned WAV with `MediaPlayer`/`AudioTrack`. Cache audio files by SHA-256 of the text in `cacheDir` (fixed phrases will be reused a lot). Pre-warm the fixed phrases from Section 8.3 when the bubble starts.
- Interface: `speak(textUrdu, textRoman, onDone)`, `stop()`.
- Speaking rate slightly slower than default (0.9) for elderly users.

### 5.12 PlannerClient
- OkHttp, base URL and app key from `AppConfig` (Settings, stored in SharedPreferences).
- Header `X-Rehnuma-Key: <key>`.
- Timeouts: connect 3 s, read 8 s. One retry on timeout/5xx.
- Records `plannerMs` in metrics.

### 5.13 LearningStore and PlanCache
See Section 9.

### 5.14 MetricsLogger
Appends one JSON line per step to `filesDir/metrics.jsonl`:
`{sessionId, step, goalKey, package, action, readMs, plannerMs, ttsStartMs, timeToUserTapMs, outcome, fromCache, guidanceLevel, ts}`.
Never logs screen text. The Flutter Metrics screen reads summaries through the channel and can export the file via the share sheet.

---

## 6. Backend (FastAPI)

### 6.1 Endpoints
| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/v1/health` | `{status:"ok", model: <LLM_MODEL>}` |
| POST | `/v1/plan` | Choose the next step |
| POST | `/v1/stt` | Transcribe Urdu speech (fallback path) |
| POST | `/v1/tts` | Urdu speech audio (fallback path) |

All endpoints except health require header `X-Rehnuma-Key` equal to env `REHNUMA_APP_KEY`; otherwise 401.

### 6.2 `/v1/plan` request
```json
{
  "session_id": "3f1c...",
  "goal_text": "Beti Sara ko WhatsApp pe photo bhejni hai",
  "step_index": 2,
  "history": [
    {"step": 1, "instruction_roman": "Yeh hara WhatsApp icon dabayein", "target_label": "WhatsApp", "outcome": "done"}
  ],
  "screen": {
    "package": "com.whatsapp",
    "window_title": null,
    "width": 1080,
    "height": 2400,
    "elements": [
      {"id": 1, "text": "Chats", "cls": "TextView", "clickable": true, "bounds": [0, 300, 360, 400]},
      {"id": 2, "text": "Sara", "label": "Sara · Kal milte hain", "cls": "View", "clickable": true, "bounds": [0, 420, 1080, 560]},
      {"id": 3, "desc": "New chat", "cls": "ImageButton", "clickable": true, "bounds": [900, 2100, 1040, 2240]}
    ]
  },
  "prefs": {"caption": "roman"}
}
```

### 6.3 `/v1/plan` response
```json
{
  "action": "tap",
  "element_id": 2,
  "scroll_direction": null,
  "say_urdu": "سارہ کے نام پر دبائیں",
  "say_roman": "Sara ke naam pe dabayein",
  "goal_key": "whatsapp_send_photo",
  "confidence": 0.92,
  "reason": "Chat with Sara is visible; opening it is the next step.",
  "latency_ms": 1430
}
```
- `action` ∈ `tap | type | scroll | back | home | ask | done | cant_help`.
- `element_id` required for `tap`, `type`, `scroll`; must be one of the ids in the request. Null otherwise.
- `scroll_direction` ∈ `up | down | left | right` for `scroll`.
- `say_urdu` (Urdu script, for TTS) and `say_roman` (Roman Urdu, for captions): one short sentence each, at most 12 words, simple everyday words, polite form ("dabayein", "likhein").
- For `type`: the instruction must tell the user what to type, and the user types it themselves.
- For `ask`: `say_*` is the clarifying question (for example which contact to send to).
- `goal_key`: short snake_case English slug for the task, stable across sessions (used by PlanCache and LearningStore).
- `confidence` 0 to 1. `reason` is English, max 25 words, for logs only.

### 6.4 Planner logic (`app/planner.py`)
1. Validate request with Pydantic. Reject screens with more than 120 elements (422).
2. Re-apply masking to all element strings (same regex as Section 5.3).
3. Build the prompt (Section 6.6) with the goal, last 8 history items and a compact element list. Compact element line format (one per element):
   `#2 [View, clickable] "Sara · Kal milte hain" @ (0,420)-(1080,560)`
   Include `text`, `desc`, `hint`, `label`, `viewId` when present, joined with " | ".
4. Call the LLM provider with structured JSON output (Gemini `response_mime_type="application/json"` and a `response_schema` matching 6.3, `temperature=0.2`).
5. Validate the response: action allowed, `element_id` present when required and contained in the request ids, sentence lengths within limits. If invalid, retry **once** with an extra user message explaining the exact error. If still invalid, return `{"action": "cant_help", ...}` with the CANT_HELP phrases.
6. Log one JSON line (Section 6.8) and return.

### 6.5 LLM provider interface (`app/llm/base.py`)
```python
class LLMProvider(Protocol):
    async def plan(self, system: str, user: str, schema: type[BaseModel]) -> dict: ...
    async def transcribe(self, audio: bytes, mime: str) -> dict: ...
```
Implement `GeminiProvider` with the `google-genai` SDK. Model IDs come from env (`LLM_MODEL`, `STT_MODEL`). At build time, check Google's current model list and choose a current Flash model for both; write the chosen IDs into `.env.example` and `docs/DECISIONS.md`.

### 6.6 System prompt (`app/prompts.py`)
Use this text (tune wording if needed, keep the rules):

```
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
4. If the goal needs another app and the screen is not the home screen or that app, choose "home".
5. If the goal is unclear (for example which contact or which photo), choose "ask" and ask one short question.
6. If the goal is already achieved on this screen, choose "done".
7. If the next step would be entering a PIN, password, OTP or confirming a payment, choose "done"
   and say that the user should do this part themselves.
8. If the task is impossible from here or unsafe, choose "cant_help".
9. Write say_urdu in simple Urdu script and say_roman in simple Roman Urdu. One short sentence,
   at most 12 words, polite form ("dabayein", "likhein"). Describe the element the way a user sees it:
   by its colour, icon or text (for example "hara WhatsApp icon", "neeche wala bheejne ka teer").
10. Never mention element ids, "element", "node" or technical words to the user.
11. Return only JSON that matches the schema.
```

User message template:
```
GOAL: {goal_text}
STEP: {step_index}
HISTORY:
{history_lines or "none"}
SCREEN (package={package}, size={width}x{height}):
{element_lines}
```

Add 2 few-shot examples in `prompts.py` (WhatsApp chat list → open contact; Settings display screen → font size) as prior user/assistant turns.

### 6.7 `/v1/stt` and `/v1/tts`
- `/v1/stt`: multipart field `audio` (`audio/wav`, `audio/ogg`, `audio/mp4`). Ask the model to transcribe Urdu or Punjabi speech, returning `{"text_urdu": "...", "text_roman": "...", "latency_ms": n}`. Max 20 s audio.
- `/v1/tts`: body `{"text": "<Urdu script>"}`. Use Google Cloud Text-to-Speech with a Gemini-TTS model and `language_code="ur-PK"` (Urdu (Pakistan) is listed as a preview language). Return `audio/wav`. Cache results on disk by SHA-256 of the text. If the cloud call fails, return 503 and the app uses its on-device voice or captions only.
- Credentials: `GOOGLE_APPLICATION_CREDENTIALS` for Cloud TTS; `GEMINI_API_KEY` for the Gemini API.

### 6.8 Logging
`LOG_DIR/plan.jsonl`, one line per call: `{ts, session_id, step_index, package, n_elements, goal_text, action, element_id, goal_key, confidence, latency_ms, retried, valid}`. Element texts are not logged by default; env `LOG_SCREENS=true` (development only) also logs the masked element list for building evaluation snapshots.

### 6.9 Config (`.env.example`)
```
GEMINI_API_KEY=
LLM_MODEL=            # current Gemini Flash model id, see docs/DECISIONS.md
STT_MODEL=            # same family, audio-capable
GOOGLE_APPLICATION_CREDENTIALS=
TTS_MODEL=            # Gemini-TTS model id from Cloud Text-to-Speech docs
REHNUMA_APP_KEY=change-me
LOG_DIR=./logs
LOG_SCREENS=false
```
Run locally with `uvicorn app.main:app --host 0.0.0.0 --port 8000`. For the demo, the phone reaches the backend over the same Wi-Fi or a tunnel; the base URL is set in the app's Settings.

### 6.10 Backend tests (pytest)
- Schema validation accepts the example request and rejects bad ones.
- Masking replaces long numbers and keeps short ones.
- Planner rejects an `element_id` not in the screen and retries once (mock the provider).
- Invalid output twice → `cant_help`.
- Auth: missing/wrong key → 401.

---

## 7. Safety and privacy summary
| Risk | Control |
| --- | --- |
| App taps for the user | No action APIs (rule 1), no `canPerformGestures`, grep check in CI |
| Screen images leak | No screenshot APIs at all (rule 2) |
| PIN/password/OTP exposure | SafetyGuard pauses and uploads nothing (5.4) |
| Background snooping | Events ignored unless a session is active (rule 4) |
| Account numbers, CNICs | Long-number masking on phone and server (5.3) |
| Payment confirmation | Planner rule 7: user does that part alone |
| Stored data | Only metrics without screen text; debug snapshots only on explicit user action |

---

## 8. Language and content

### 8.1 Voice and tone
Rehnuma speaks as a calm, respectful helper (feminine first person: "dekhti hoon", "sakti"). Short sentences. No technical words. Praise success briefly.

### 8.2 Captions
Default Roman Urdu (easier for demo judges); Settings toggle for Urdu script with Noto Nastaliq Urdu.

### 8.3 Fixed phrases (store in Kotlin `Phrases.kt`; pre-cache TTS)
| Key | Roman Urdu | Urdu |
| --- | --- | --- |
| ASK_GOAL | Bataiye, kya karna hai? | بتائیے، کیا کرنا ہے؟ |
| THINKING | Ek second, dekh rahi hoon. | ایک سیکنڈ، دیکھ رہی ہوں۔ |
| SENSITIVE_PAUSE | Yeh screen aap khud bharein. Main ise nahi dekhti. | یہ اسکرین آپ خود بھریں۔ میں اسے نہیں دیکھتی۔ |
| DEVIATION | Koi baat nahi, ab yahan se chalte hain. | کوئی بات نہیں، اب یہاں سے چلتے ہیں۔ |
| WRONG_TAP | Yeh nahi, jahan haath hai wahan dabayein. | یہ نہیں، جہاں ہاتھ ہے وہاں دبائیں۔ |
| DONE | Shabash! Kaam ho gaya. | شاباش! کام ہو گیا۔ |
| DIDNT_HEAR | Maaf kijiye, samajh nahi aaya. Dobara bataiye. | معاف کیجیے، سمجھ نہیں آیا۔ دوبارہ بتائیے۔ |
| NETWORK_SLOW | Internet aahista hai, dobara koshish kar rahi hoon. | انٹرنیٹ آہستہ ہے، دوبارہ کوشش کر رہی ہوں۔ |
| CANT_HELP | Is kaam mein main abhi madad nahi kar sakti. | اس کام میں میں ابھی مدد نہیں کر سکتی۔ |
| YOUR_TURN | Ab aap khud karein, main dekh rahi hoon. | اب آپ خود کریں، میں دیکھ رہی ہوں۔ |

### 8.4 Quick tasks (bubble panel and Home screen)
1. "WhatsApp pe photo bhejni hai"
2. "Phone ke likhai bari karni hai" (font size)
3. "WiFi on karna hai"
4. "Kisi ko call karni hai"
5. "Mobile load karna hai" (demo of the PIN safety stop in a wallet app)

---

## 9. Help fading and speed

### 9.1 LearningStore (`filesDir/learning.json`)
Key: `goalKey + "|" + package + "|" + targetKey`, where `targetKey` is the target's `viewId` if present, else its normalized label.
Value: `{successes: Int, lastSeenTs: Long}`.
Guidance level for a step, computed before pointing:
| Successes on this step | Level | Behaviour |
| --- | --- | --- |
| 0 to 1 | FULL | Hand animation + ring + caption + full spoken instruction |
| 2 to 3 | HINT | Short spoken hint only (`say_roman` trimmed to its first clause) and caption; hand appears only after 6 s without the expected action |
| 4+ | SILENT | Speak `YOUR_TURN` once per session; hand appears only after 10 s without the expected action |
Increment `successes` on verified success. Settings → "Demo mode: hamesha poori madad" forces FULL. Settings → "Seekhna reset karein" clears the store.

### 9.2 PlanCache (`filesDir/plan_cache.json`)
- Screen signature: SHA-1 of `package + sorted(first 15 non-empty labels)`.
- After a session finishes with `done`, store for each step: `{goalKey, signature, target: {viewId, normLabel, cls}, say_urdu, say_roman, action}`.
- At `PLANNING`, if the session's `goalKey` is known (from the first planner response of this session) and the current signature matches a cached step whose target is found in the current snapshot (match by `viewId`, else normalized label), use the cached step without calling the backend. Mark `fromCache=true` in metrics.
- The first step of every session always calls the backend (to get `goalKey`).
- If a cached step's target is missing, fall back to the backend.

### 9.3 Pre-fetch
When a new screen settles, start reading and planning immediately; do not wait for TTS of the previous step to finish. Show the bubble "thinking" state during planning.

---

## 10. Flutter app screens

All screens bilingual (Urdu primary, English secondary line), large touch targets (≥ 56 dp), simple layout.

1. **Onboarding (3 pages):** what Rehnuma does; the three promises ("Main khud kuch nahi dabati", "Sirf zaroorat pe dekhti hoon", "PIN wali screen nahi dekhti"); start button.
2. **Permissions checklist:** live status rows with buttons:
   - Accessibility service on → opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`. Show help text for Android 13+ sideloaded apps: "Agar setting band ho: App info → ⋮ → Allow restricted settings."
   - Microphone (runtime permission).
   - Notifications (Android 13+).
   Status refreshes on resume via `isServiceEnabled`.
3. **Home:** big button "Rehnuma chalu karein / band karein", the 5 quick tasks (each starts a session with that goal and shows the bubble), current state text from `EventChannel`.
4. **Settings:** backend base URL, app key, voice in (Device / Cloud), voice out (Device / Cloud), caption script (Roman / Urdu), talk mode (Hold / Tap), demo mode, reset learning, debug overlay toggle.
5. **Metrics:** sessions count, success rate, median `plannerMs`, median step time, cache hit rate; button to export `metrics.jsonl`; debug section with "Save current screen snapshot".

### 10.1 MethodChannel `rehnuma/control`
| Method | Args | Returns |
| --- | --- | --- |
| `isServiceEnabled` | none | bool |
| `openAccessibilitySettings` | none | void |
| `showBubble` / `hideBubble` | none | void |
| `startQuickTask` | `{goal: String}` | void |
| `setConfig` | map of settings | void |
| `getConfig` | none | map |
| `getMetricsSummary` | none | map |
| `exportMetrics` | none | file path |
| `resetLearning` | none | void |
| `setDebugBoxes` | `{enabled: bool}` | void |

### 10.2 EventChannel `rehnuma/events`
Emits `{state, goalText?, step?, captionRoman?, error?}` on every state change.

---

## 11. Debug and evaluation tools

- **Debug Boxes overlay:** draws every element's bounds with its id in small labels. Used on Day 1 to confirm that target apps expose labels and that coordinates align.
- **Save snapshot:** writes the current masked `ScreenSnapshot` plus a field `"goal_text": ""` and `"expected": []` to `getExternalFilesDir("snapshots")/<ts>.json`, then opens the share sheet. The team fills `goal_text` and `expected` (acceptable element ids and action) and copies files into `backend/eval/snapshots/`.
- **`backend/eval/eval_planner.py`:** runs every snapshot through the planner (same code path as `/v1/plan`), reports: step accuracy (chosen id ∈ expected), action accuracy, invalid-output rate, latency p50/p90, and a CSV of failures. Target for the demo: 50 snapshots across WhatsApp, Settings and one wallet app.

---

## 12. Acceptance tests (run on the demo phone)

| ID | Scenario | Pass condition |
| --- | --- | --- |
| A1 | Accessibility service enabled, bubble visible over other apps | Bubble stays on top of WhatsApp and Settings; touches on the app below work normally |
| A2 | Debug boxes on WhatsApp chat list, Settings, wallet app home | Boxes align with real elements within ±10 px; most interactive elements have labels |
| A3 | Voice goal "WhatsApp pe Sara ko photo bhejni hai" from home screen | Full guidance to a sent photo in at least 4 of 5 runs; no pointer on a wrong element |
| A4 | User taps a different element mid-task | DEVIATION spoken once; guidance continues from the new screen |
| A5 | Font size task done 4 times | Run 1 FULL; runs 3+ show reduced guidance per Section 9.1 |
| A6 | Wallet app "Mobile load" until the PIN screen | SENSITIVE_PAUSE spoken; backend `plan.jsonl` has no entry for that screen; pointer hidden |
| A7 | Cancel from bubble "×" and from notification | All overlays removed within 300 ms; STT/TTS stopped |
| A8 | Backend down | NETWORK_SLOW spoken, retry offered, app does not crash |
| A9 | `tools/check_no_actions.sh` | Passes (no forbidden APIs) |
| A10 | Repeat of a completed task | At least one step served from PlanCache (`fromCache=true`) |
| A11 | Metrics export | `metrics.jsonl` has latency fields for every step; no screen text inside |

Performance targets (measure, report in Metrics): screen read < 150 ms; planner p50 < 2.0 s; time from screen settle to pointer visible p50 < 2.5 s; cached step < 300 ms.

---

## 13. Build phases

Build strictly in this order. Each phase ends with a commit and a short note in `docs/DECISIONS.md`.

**Phase 0: Skeleton (day 1)**
- Flutter app with the 5 screens as placeholders, MethodChannel/EventChannel wiring, Kotlin package structure, accessibility service registered with the XML in 5.1, `tools/check_no_actions.sh`.
- Backend skeleton with `/v1/health`, auth, config, pytest setup.
- Done when: app installs, service can be enabled, `isServiceEnabled` returns true, health endpoint responds.

**Phase 1: See the screen (day 1, the go/no-go check)**
- ScreenReader, Masking, SafetyGuard, Debug Boxes overlay, Save snapshot.
- Done when: A2 passes on WhatsApp, Settings and the chosen wallet app; snapshots export correctly. If a target app exposes no usable labels, record it in DECISIONS.md and replace it in the demo.

**Phase 2: Point (days 2 to 3)**
- BubbleOverlay (drag, states), PointerOverlay (ring, hand animation, caption), manual test mode: tap an element id in Debug Boxes to point at it.
- Done when: A1 passes and pointing at any chosen element is visually exact.

**Phase 3: Plan (days 3 to 4)**
- Backend `/v1/plan` with prompt, schema, validation, retry, logging, tests. PlannerClient in Kotlin.
- Typed goal (Roman Urdu text box) drives a real plan → pointer.
- Done when: backend tests pass; a typed goal shows the correct first pointer on the home screen.

**Phase 4: Guide loop (days 4 to 5)**
- GuidanceSession state machine, SettleDetector, StepVerifier, history, limits, cancel.
- Done when: A3 (with typed goal), A4, A7 and A8 pass.

**Phase 5: Voice (day 6)**
- SpeechInput (device + cloud fallback), SpeechOutput (device + cloud fallback), fixed phrase pre-cache, `/v1/stt`, `/v1/tts`.
- Done when: A3 passes with a spoken goal in a quiet room; fallbacks work when forced in Settings.

**Phase 6: Safety and fading (day 7)**
- Sensitive pause flow end to end, LearningStore, guidance levels, demo mode, PlanCache.
- Done when: A5, A6, A10 pass.

**Phase 7: Metrics and evaluation (day 8)**
- MetricsLogger, Metrics screen, export, `eval_planner.py`, 50 labeled snapshots.
- Done when: A11 passes and the eval report prints accuracy and latency.

**Phase 8: Polish and rehearsal (day 9)**
- Visual polish, bilingual strings, onboarding copy, error states, rehearse the 3 demo flows, record a backup demo video.
- Done when: all acceptance tests pass on the demo phone twice in a row.

---

## 14. Development notes

- Test on a real Android 13 or 14 phone with WhatsApp signed in (an emulator is fine for Phases 0 to 2 only).
- Quick enable during development (USB debugging):
  ```
  adb shell settings put secure enabled_accessibility_services pk.rehnuma.app/pk.rehnuma.app.service.RehnumaAccessibilityService
  adb shell settings put secure accessibility_enabled 1
  ```
- Use a dedicated test contact ("Sara") and a test photo for the WhatsApp flow.
- Pick the wallet app on day 1 and stop the demo at its PIN screen. Never enter a real PIN during testing with debug logging on.
- Keep all user-visible strings in one place so the team can fix Urdu wording quickly.
- Code style: Kotlin coroutines for async work on the core side; no blocking calls on the main thread except where Android requires it (SpeechRecognizer setup, WindowManager).

---

## 15. Open questions (decide on day 1, write answers in DECISIONS.md)

1. Which wallet app is used for the PIN safety demo, and does it expose accessibility labels?
2. Does `SpeechRecognizer` with `ur-PK` work well enough on the demo phone, or should Cloud be the default?
3. Is an Urdu on-device TTS voice installed on the demo phone, or is Cloud TTS required?
4. Measured planner latency with the chosen Gemini Flash model over the venue-like network.
5. Where the backend runs during the demo (laptop on the same hotspot vs a small cloud VM).
