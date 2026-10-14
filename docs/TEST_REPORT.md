# Test report (2026-10-14)

What was tested before handing over, and what still needs the demo phone.

## Automated tests

| Suite | Command | Result |
| --- | --- | --- |
| Backend (FastAPI, planner, STT/TTS, eval harness, mock) | `cd backend && .venv/Scripts/python -m pytest` | 61 passed |
| Kotlin core (JVM) | `cd app/android && ./gradlew :app:testDebugUnitTest` | 95 passed |
| Flutter (models + all five screens against a fake core) | `cd app && flutter test` / `flutter analyze` | 17 passed / no issues |
| Safety check (A9) | `bash tools/check_no_actions.sh` | PASS |

Kotlin coverage: masking (11), SafetyGuard rules a–d with the "اپنا" and long-chat-message negatives (23), element ordering, signatures and target matching (8), PlannerClient retries/timeouts/auth against a mock server (7), the guidance session on virtual time (30: happy path, deviation, wrong tap, no-click fallback, sensitive pause and resume, PIN sheet on the same window, network error and retry, cancel, 25-step limit, ask/answer, own-app home step, re-plan when the screen moves during planning, typing, voice states, fading levels, plan cache, metrics, pointer following a moved target), learning store and plan cache (9), metrics (5), WAV (2).

## Emulator run

Pixel 10 Pro image (Android API 37), backend on the PC with the development mock planner (`LLM_PROVIDER=mock`, scripted with `MOCK_SCRIPT=eval/scripts/font_size_emulator.json` for the repeat runs). No Gemini key or Cloud TTS credentials were available, so planning quality and the Urdu voice are **not** tested here.

| ID | Result on the emulator | Evidence |
| --- | --- | --- |
| A1 | Pass | Bubble stays over Settings, launcher, YouTube, notification shade; taps through the pointer overlay reach the app (Settings opened "Display size & text") |
| A2 | Pass (Settings, launcher, dialer) | Debug Boxes align at offset 0,0; warm reads 4–56 ms. WhatsApp and the wallet app still to check on the phone |
| A3 | Pass with typed, quick-task and (mock) spoken goals | Home step when Rehnuma's own screen is in front → launcher → Settings → Display → Display size & text → done; spoken goal: 2.1 s of audio → transcript in 0.76 s → pointer |
| A4 | Pass | Tapped YouTube while the hand pointed at Phone: DEVIATION, next request carried `history_outcomes: ["done","deviated"]` |
| A5 | Pass | Five font-size runs: FULL, FULL, HINT, HINT, SILENT for both steps (from `metrics.jsonl`); HINT showed only "Display size and text dabayein" without hand; SILENT showed only "Ab aap khud karein…" |
| A6 | Pass (Android "Set a PIN" screen as the stand-in) | SENSITIVE_PAUSE with lock bubble and caption; backend plan calls stayed at 14 while on and typing into the PIN screen; metrics only `{event: sensitive_pause, package}`; guidance resumed after leaving. The screen-lock option lists were correctly *not* flagged |
| A7 | Pass | × chip: pointer gone in the first screenshot after the tap. Notification "Band karein": all overlay windows gone within 0.4 s, notification cancelled |
| A8 | Pass | Backend stopped: NETWORK_SLOW caption, then ERROR with ↻ and "Internet ka masla hai…"; after restart a bubble tap retried the same step |
| A9 | Pass | `check_no_actions.sh` |
| A10 | Pass | From run 2 the second step came from PlanCache (0–1 ms, settle→pointer ~30 ms); 11 backend calls instead of 15 |
| A11 | Pass | Exported `metrics-*.jsonl` via the share sheet: every guided step has `plannerMs` and `timeToUserTapMs`, no screen or instruction text. `ttsStartMs` is empty only because the emulator has no voice |

Also checked: onboarding from cleared data, the real mic and notification permission dialogs, "Open settings" opening Android's accessibility page (the only launch), Settings **Check server** (OK / wrong key / unreachable), a session with a wrong key ("App key ghalat hai. Settings dekhein."), "Seekhna reset karein" emptying `learning.json`, debug snapshot export, and the Metrics screen numbers.

## Second pass (same day, 08:40–09:10)

| Area | Result |
| --- | --- |
| **Release build** (R8 minified, not debuggable) | Full guided task, Metrics screen and Check server work on the emulator; no serialization or reflection crashes |
| Tap-to-talk mode | Tap starts listening, second tap sends; 1.5 s long-press opens the panel; drags never start listening |
| Urdu-script captions | Instructions, WRONG_TAP and questions render right-to-left in Urdu script |
| ASK flow | Question captioned; the spoken answer is appended as `User answer: …` in the same session and guidance continues |
| TYPE step | Pointer on the search field; typing, then 3 s quiet, finishes the step |
| WRONG_TAP | Pressing "5" on the dialer while the hand points at Home: caption flashes "یہ نہیں، جہاں ہاتھ ہے وہاں دبائیں", step stays, no re-plan |
| HINT hesitation reveal | Caption only at first; ring and hand appear after 6 s |
| Bubble drag | Follows the finger, snaps to the nearest edge, side remembered; × chip moves to the side facing the centre |
| Flutter screens | 13 new widget tests: start-up routing, onboarding, permission buttons and resume refresh, start/stop and all 5 quick tasks, service-off redirect, status card, every Settings control, Check server (OK / wrong key / unreachable), Metrics numbers, dashes, export, snapshot |

New bug found and fixed: on a TYPE step the caption covered the bottom row of keyboard keys. Captions now sit just above the keyboard (only the keyboard window's bounds are read).

**Real phone:** the release APK is installed on an OPPO CPH2001 (Android 11, ColorOS 11.1, 1080×2400, WhatsApp installed). It starts and shows onboarding without crashing. It has Google TTS and Google speech recognition. The guided flows have not been run on it yet: the accessibility service has to be switched on by hand on the phone.

## Bugs found and fixed during the run

1. **Response bodies read on the main thread** (`NetworkOnMainThreadException`, shown as "Internet ka masla"): showed up after an STT upload. HTTP attempts now run on `Dispatchers.IO`.
2. **Stale pooled connections** made one step wait 8 s; idle connections are now dropped after 4 s.
3. **Pointer stayed at old coordinates when the list moved** (expanding a notification shifted "Clear all"): the pointer now follows the same element on every settled re-read, and taps are matched against the new position.
4. WRONG_TAP, DEVIATION and YOUR_TURN were spoken but not captioned (invisible without a voice); now captioned too.
5. Smaller: Enter submitted the panel twice; the panel's text box was hidden by the keyboard; the ring was cut off on full-width rows; the nav arrow sat under the hand; mixed-script Urdu help text; Flutter's default launcher icon.

## Still to do on the demo phone

- Real planner accuracy and latency: add `GEMINI_API_KEY`, run `python -m eval.eval_planner`, and capture ~50 labelled snapshots (WhatsApp, Settings, wallet app).
- Urdu voice: is an on-device `ur-PK` TTS voice installed, does `SpeechRecognizer` handle `ur-PK` (SPEC 15 Q2–Q3)? Otherwise set up Cloud TTS credentials or rely on captions.
- A2/A3/A6 with WhatsApp and the chosen wallet app; check the wallet's PIN screen really triggers SafetyGuard.
- Run the full checklist twice in a row (SPEC 13 Phase 8 "done").
