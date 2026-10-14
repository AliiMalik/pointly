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

## Phase 4: Guide loop (2026-10-14)

- **Pure-Kotlin core.** `GuidanceSession`, `StepVerifier` and `SettleDetector` never touch Android types. The service turns each `AccessibilityEvent` into a `ScreenEvent` (type, package, window id, and for clicks/typing/scrolling the source's view id, masked label and bounds; the node is released immediately) and implements small `SessionUi`/`SessionVoice` ports with the overlays. 17 JVM tests drive the whole loop on virtual time.
- `TYPE_WINDOWS_CHANGED` is not forwarded: it also fires whenever our own overlays appear. Events from `pk.rehnuma.app` are ignored too.
- **Settle** (SPEC 5.6): after a tap or window change the 500 ms quiet period starts *now* (so the app has time to begin its transition); for the very first read a screen that is already quiet is read immediately. Hard cap 2.5 s.
- **Tap verification** (SPEC 5.7): source bounds containing the target's centre is a match; a shared view id only matches if the labels agree (WhatsApp chat rows all share one id). A click with no source, or no click event at all, is judged by the next settled screen: window/package changed or target gone → progress (or deviation if a mismatched tap was seen); same screen after a mismatched tap → WRONG_TAP once per step.
- **Deviation** stops the old instruction, says DEVIATION, records `outcome: deviated`, and plans from the new screen.
- **Screen changed while planning**: the plan is checked against a fresh read; if the screen signature or window changed, the step is re-planned (up to 2 times) without counting an extra step.
- **Rehnuma's own screen in front** (e.g. a quick task tapped on Home): the reader never reads our UI; the session shows a local "Home button dabayein ya neeche se upar swipe karein" step with the nav pointer, no planner call.
- **`done`** says DONE ("Shabash! Kaam ho gaya.") unless the planner's sentence contains "khud" (the user must finish a PIN/payment themselves), in which case that sentence is said instead. `cant_help` and the 25-step limit say CANT_HELP and end.
- **Errors** show plain Urdu/Roman Urdu captions ("Internet ka masla hai. Dobara koshish ke liye bubble dabayein.") with the ↻ badge; technical details only go to the Flutter status line.
- **Phase 4 has no speech yet**: a caption-only voice stands in until Phase 5.
- **Connection pool**: OkHttp drops idle connections after 4 s (uvicorn closes keep-alive at 5 s). On the emulator a dead pooled connection made one step wait for the full 8 s read timeout.
- **Emulator run** (mock planner): quick task from Home → home-nav step → Home → launcher plan; tapping YouTube instead of the pointed Phone icon logged `history_outcomes: ["done", "deviated"]` and re-planned (A4); backend stopped → NETWORK_SLOW then ERROR with ↻, no crash; after restart a bubble tap retried the same step (A8); tapping the target moved to step 2 on the next screen (A3 with typed goal); × removed pointer and caption immediately (A7, bubble path).

## Phase 5: Voice (2026-10-14)

- **Speech out**: one queue for both engines, so DEVIATION/THINKING finish before the next instruction; a finished step stops old speech first. Device `TextToSpeech` is used only if an installed `ur*` voice exists (speech rate 0.9, `USAGE_ASSISTANT` audio). Otherwise `/v1/tts` WAV, cached in `cacheDir/tts/<sha256>.wav` and played with `MediaPlayer`. The ten fixed phrases are pre-fetched when the bubble starts. TTS requests are not retried, and after a failure the cloud voice is skipped for 60 s: captions carry the instruction meanwhile.
- **Backend `/v1/tts`** caches by SHA-256 of model, voice and text; Gemini-TTS gets a style prompt ("calm, warm, respectful female voice, a little slowly"). `GOOGLE_APPLICATION_CREDENTIALS` from `.env` is copied into the process environment because the Google client only reads it from there. Returns 503 when not configured.
- **Speech in**: device `SpeechRecognizer` (`ur-PK`, free form, partial results shown in the caption). A "language not supported/unavailable" error while the bubble is still held switches to cloud recording on the spot and remembers it for later sessions. Cloud path: `AudioRecord` (VOICE_RECOGNITION, 16 kHz mono 16-bit) until release or 20 s, wrapped as WAV, `POST /v1/stt`. Under 0.25 s counts as silence. If a recognizer never answers after release, the result times out at 5 s.
- The goal text is the Roman Urdu transcript when there is one (cloud), else the Urdu script (device); the planner accepts both.
- **Session**: LISTENING shows the mic bubble and "Bataiye, kya karna hai?"; TRANSCRIBING (cloud only) shows thinking dots. A blank result says DIDNT_HEAR and returns to what was happening (still ASKING, or re-plans the same goal, or idle). A new goal says THINKING ("Ek second, dekh rahi hoon") as SPEC 4 step 3 describes. Holding the bubble during guidance starts a new goal; during ASKING it is the answer.
- **Bug fixed during emulator testing**: OkHttp resumed our coroutine on the main thread and the response body was read there, which threw `NetworkOnMainThreadException` whenever the body wasn't already buffered (it showed up after an STT upload). Each attempt now runs on `Dispatchers.IO`; only the retry callback (which touches the UI) runs on the caller's thread.
- **Mock STT**: with `LLM_PROVIDER=mock`, `MOCK_STT_TEXT` is returned as the transcript so the spoken flow can be rehearsed on an emulator without a microphone.
- **Emulator check**: holding the bubble shows the mic UI and starts Android's recognizer; silence → recognizer error 7 → DIDNT_HEAR. With Voice in = Cloud, the WAV upload reaches `/v1/stt` (200) and the mock transcript drove a plan: 2.1 s of audio, transcript in 0.76 s, hand on "Display size & text" ~0.8 s after release. The emulator has **no on-device Urdu TTS voice** and this PC has no Cloud TTS credentials, so speech fell back to captions only, as designed. Check SPEC 15 questions 2 and 3 on the demo phone.

## Phase 6: Safety and fading (2026-10-14)

- **Labels are hashed at rest.** SPEC 9 keys LearningStore by the target's view id or normalized label, and PlanCache stores `normLabel`. Labels can hold contact names and message previews, so both stores keep an 8-byte SHA-1 of the normalized label instead; matching is unchanged. The spoken sentences (`say_urdu`, `say_roman`) are still cached, as SPEC 9.2 requires.
- **Levels** (SPEC 9.1) per `goalKey|package|targetKey`: 0–1 successes FULL, 2–3 HINT, 4+ SILENT; Demo mode forces FULL. HINT speaks and captions only the first clause (split at `، , ؛ ; . ۔ ! ?`), with no ring or hand until 6 s pass. SILENT says YOUR_TURN once per session and shows nothing until 10 s. On hesitation the full pointer and caption appear (without repeating speech). A bubble tap always repeats with full help.
- A success is counted only for steps that end `done` (not deviated or cancelled). "Seekhna reset karein" clears LearningStore only; the plan cache is a speed cache, not learning.
- **PlanCache**: when a session ends with `done`, its `done` steps are stored per `goal_key` (newer steps replace older ones for the same screen signature; 30 steps × 50 goals max). From step 2 on, a step whose signature matches and whose target is found (view id, then label hash) is used without calling the backend; `fromCache=true` in metrics. Step 1 always asks the backend to learn `goal_key`.
- **Same-window sensitive screens**: while the pointer is up, every settled re-read goes through SafetyGuard first. A PIN keypad or password sheet opening on the same window (a common wallet-app pattern) hides the pointer and pauses straight away, even though the verifier would have seen "unchanged".
- **Metrics hooks**: the session now reports one `StepMetrics` per step (read, planner, speech-start, user-tap and settle→pointer timings; outcome; level; fromCache) and `onSensitivePause(package)` with nothing else. The logger arrives in Phase 7.

## Phase 7: Metrics and evaluation (2026-10-14)

- **`metrics.jsonl`** lines carry `sessionId, step, goalKey, package, action, readMs, plannerMs, ttsStartMs, timeToUserTapMs, settleToPointerMs, outcome, fromCache, guidanceLevel, ts` (SPEC 5.14 plus `settleToPointerMs` for the SPEC 12 "settle to pointer" target). Outcomes: `done`, `deviated`, `cancelled`, `interrupted` (user spoke a new goal), `completed`, `asked`, `cant_help`. Sensitive screens add only `{sessionId, package, event: "sensitive_pause", ts}`. `ttsStartMs` is measured from the pointer appearing to audio starting; it's empty when no voice is available (captions only).
- **Summary** (Metrics screen): sessions, steps, success rate (sessions with a `completed` step), median planner ms (network steps only), median cached-step ms, median step time (pointer → user's tap), median read ms, median settle→pointer ms, cache hit rate (of guided steps), sensitive pauses.
- **Export** copies the log to `filesDir/exports/` and shares that copy. The FileProvider now exposes only `exports/` and the external `snapshots/` folder, so `learning.json` and `plan_cache.json` can't be shared by mistake.
- **`eval_planner.py`** calls `Planner.plan_detailed` (the `/v1/plan` code path, plus valid/retried flags) for every labelled file in `eval/snapshots/`, with up to 3 in parallel. Unlabelled files (empty `goal_text`/`expected`) are skipped and listed. Failures go to `eval/reports/failures-<time>.csv` (git-ignored). `--mock` checks the harness without a key.
- **Bundled eval cases (9)**: 8 real emulator screens captured with the app's own "Save snapshot" (Settings main ×2 goals, Display & touch, Display size & text, Network & internet, Internet with Wi‑Fi already on → `done`, launcher → Phone, dialer with no contact named → `ask`) and 1 synthetic WhatsApp chat list from SPEC 6.2 (named `synthetic_…`). SPEC's target of 50 snapshots across WhatsApp, Settings and the wallet app needs the demo phone: capture with Metrics → "Save current screen snapshot" or the bubble panel's Snapshot button, fill `goal_text`/`expected`, drop into `backend/eval/snapshots/`.

## Phase 8: Polish and testing (2026-10-14)

- **Check server** in Settings saves the URL/key, then calls `/v1/health` and the authenticated `/v1/ping`, and reports reachable / wrong key / OK with the model name, so the phone can be set up at the venue without guessing.
- **Pointer follows the target**: on every settled re-read while pointing, if the planned element is still on the same window but at new bounds (a list shifted, a notification expanded, a new chat message pushed rows down), ring and hand glide there and taps are matched against the new bounds. Found on the emulator when expanding a notification left the pointer on the wrong row.
- **Captions for every spoken phrase**: WRONG_TAP and YOUR_TURN briefly replace the instruction caption (3.5 s); DEVIATION shows while the next step is planned. Without an Urdu voice nothing important is audio-only.
- **Scripted mock** (`MOCK_SCRIPT`): rules by goal/package/window title → tap a label or say done. Used for the repeat-run tests and handy for offline rehearsal; never used unless set.
- Launcher icon: adaptive icon (yellow target ring, white centre, brand green), with a monochrome layer for themed icons.
- Emulator results and the remaining phone-only checks are in [`TEST_REPORT.md`](TEST_REPORT.md).

## Live Gemini check (2026-10-14, with the team's API key)

- **Model: `gemini-3.5-flash-lite`, fallback `gemini-3.6-flash`.** `gemini-3.8-flash` (the Phase 3 choice) was answering 503 "high demand", and its failures took ~3.5 s each. Flash-lite got all 9 eval screens right at p50 1.3 s / p90 1.7 s (SPEC target p50 < 2 s); it was the fastest of the five models probed.
- **Fallback with cooldown** (`LLM_FALLBACK_MODEL`): on 429/5xx from the main model the same request goes to the fallback, and the main model is skipped for 60 s so a busy model costs one slow failure, not one per step. 429 matters here: the key's free-tier quota ran out per model after a handful of calls; each model has its own quota.
- **SDK deadline ≥ 10 s.** The SDK sends its timeout as a server deadline and Gemini rejects anything under 10 s (`400 INVALID_ARGUMENT`); the planner's own 6.5 s `asyncio.wait_for` still cuts calls off in time for the phone.
- **Script check.** Flash-lite put Roman Urdu into `say_urdu` in 5 of 9 answers (and once slipped Telugu letters into `say_roman`). The prompt now gives an Urdu-script example and forbids Roman letters in `say_urdu`, which fixed it on its own (9/9, no retries). The validator also rejects a `say_urdu` with under 60 % Urdu-script letters or a `say_roman` with non-Latin letters, triggering the corrective retry.
- `/v1/stt` with Gemini works (200 in ~2 s, Urdu script plus Roman). Cloud TTS still needs `GOOGLE_APPLICATION_CREDENTIALS`.
- The key lives only in `backend/.env` (git-ignored); `.env.example` stays blank.

## Open questions (SPEC 15), to answer on the demo phone

1. Wallet app for the PIN demo, and whether it exposes labels: _pending_
2. `SpeechRecognizer` `ur-PK` quality on the demo phone: _pending_
3. On-device Urdu TTS voice installed: _pending_
4. Measured planner latency over a venue-like network: _pending_ (from this PC on home Wi-Fi: 1.2–2.1 s per step with gemini-3.5-flash-lite)
5. Where the backend runs during the demo: _pending_
