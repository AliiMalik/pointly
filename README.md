# Rehnuma

Android helper for first-time and low-literacy smartphone users. Hold the green bubble, say in Urdu what you want to do, and Rehnuma points with an animated hand at the one thing to press next, says it in Urdu, and waits for **you** to tap. It never taps, types or opens apps for you, never takes screenshots, and stops looking at PIN/password screens.

Built from [`docs/SPEC.md`](docs/SPEC.md); every judgement call is in [`docs/DECISIONS.md`](docs/DECISIONS.md).

```
rehnuma/
├── app/        Flutter screens + Kotlin core (accessibility service, overlays, voice, guidance loop)
├── backend/    FastAPI planner (/v1/plan, /v1/stt, /v1/tts) using Gemini, plus eval script
├── tools/      check_no_actions.sh (SPEC rule 1 / test A9)
└── docs/       SPEC.md, DECISIONS.md
```

## 1. Backend (laptop)

Needs Python 3.11+.

```bash
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt     # macOS/Linux: .venv/bin/pip
cp .env.example .env                              # then edit .env
```

In `.env` set at least `GEMINI_API_KEY` and a new `REHNUMA_APP_KEY`. The model ids are already filled in (`gemini-3.8-flash`, checked 2026-10-14). For the cloud Urdu voice also set `GOOGLE_APPLICATION_CREDENTIALS` to a service-account JSON with Cloud Text-to-Speech enabled; without it `/v1/tts` answers 503 and the phone uses its own voice or captions.

Run it so the phone can reach it on the same Wi-Fi/hotspot (allow Python through the Windows firewall when asked):

```bash
.venv/Scripts/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --timeout-keep-alive 30
```

Check: open `http://<laptop-ip>:8000/v1/health` → `{"status":"ok","model":"gemini-3.8-flash"}`.

No API key yet? `LLM_PROVIDER=mock` runs a keyword planner (and `MOCK_STT_TEXT="..."` fakes speech) so the whole loop can be rehearsed. `/v1/health` then reports `"model": "mock"`.

Tests: `.venv/Scripts/python -m pytest` (58 tests).

## 2. App (phone)

Needs Flutter 3.44+ and an Android 13/14 phone with USB debugging.

```bash
cd app
flutter build apk --debug
adb install -r build/app/outputs/flutter-apk/app-debug.apk
```

On the phone:

1. Open **Rehnuma**, go through the three onboarding pages.
2. Permissions: turn on the Rehnuma accessibility service. On Android 13+ a side-loaded app may show the setting greyed out: **App info → ⋮ → Allow restricted settings**, then try again. Allow microphone and notifications.
   During development this also works: `adb shell settings put secure enabled_accessibility_services pk.rehnuma.app/pk.rehnuma.app.service.RehnumaAccessibilityService`
3. Settings (gear icon): set **Backend base URL** to `http://<laptop-ip>:8000` and the **App key** from `.env`, then tap **Check server**. It must say *Server OK*.
4. Home: **Rehnuma chalu karein**. The bubble appears at the screen edge.

Using it: **hold** the bubble and speak, release to send. A short tap while idle opens the quick-task panel (five tasks, a Roman Urdu text box for noisy rooms, Snapshot). During guidance a short tap repeats the instruction, and **×** or the notification's **Band karein** stops everything. Settings can switch to Tap-to-talk, cloud speech, Urdu-script captions, and **Demo mode** (always full help, use it on stage).

Kotlin tests: `cd app/android && ./gradlew :app:testDebugUnitTest` (94 tests). Dart: `flutter test`.

## 3. Demo-day checklist (SPEC 12)

| ID | How to check |
| --- | --- |
| A1 | Bubble stays over WhatsApp and Settings; taps on the app below still work |
| A2 | Settings → Debug boxes overlay on WhatsApp chat list, Settings and the wallet app: boxes sit on the real buttons |
| A3 | From the home screen say "WhatsApp pe Sara ko photo bhejni hai"; follow it to a sent photo (5 runs) |
| A4 | Tap something else mid-task: hear "Koi baat nahi, ab yahan se chalte hain" once, guidance continues |
| A5 | Demo mode **off**, Settings → Seekhna reset karein, then run the font-size task 4–5 times: full help, then hint, then silent |
| A6 | Wallet app "Mobile load" until the PIN screen: "Yeh screen aap khud bharein…", no pointer, no `plan.jsonl` line for that screen |
| A7 | Cancel with × and with the notification: everything disappears at once, speech stops |
| A8 | Stop the backend: "Internet aahista hai…", then the bubble shows ↻; tap it after restarting |
| A9 | `bash tools/check_no_actions.sh` → PASS |
| A10 | Repeat a finished task: Metrics shows a cache hit rate above 0 |
| A11 | Metrics → Export metrics.jsonl: every step has timings, no screen text |

Before the demo: answer the SPEC 15 questions at the bottom of `docs/DECISIONS.md` (wallet app, Urdu speech/voice on the phone, latency, where the backend runs).

## 4. Planner evaluation (SPEC 11)

Capture screens on the phone (Metrics → *Save current screen snapshot*, or the bubble panel's *Snapshot*), share the JSON to the laptop, fill `goal_text` and `expected` (e.g. `[{"action": "tap", "element_id": 13}]`), and put it in `backend/eval/snapshots/`. Then:

```bash
cd backend
.venv/Scripts/python -m eval.eval_planner          # real model from .env
.venv/Scripts/python -m eval.eval_planner --mock   # harness only
```

It prints step accuracy, action accuracy, invalid-output rate and latency p50/p90, and writes failures to `eval/reports/`. 9 example cases are included; the target is 50 across WhatsApp, Settings and the wallet app.

## 5. Urdu font (optional)

Urdu-script captions use the system Arabic-script font unless Noto Nastaliq Urdu is bundled. To bundle it, download `NotoNastaliqUrdu-Regular.ttf` from Google Fonts and copy it to:

- `app/android/app/src/main/res/font/noto_nastaliq_urdu.ttf` (overlay captions; picked up automatically), and
- `app/assets/fonts/NotoNastaliqUrdu-Regular.ttf`, then add it under `flutter: fonts:` in `app/pubspec.yaml` for the app screens.

## Safety rules in code

- No action, gesture, global-action, screenshot or app-launch APIs: `tools/check_no_actions.sh` greps Kotlin/Dart/Java sources and the service XML (no `canPerformGestures`).
- Screen events are ignored unless a session (or the developer's Debug Boxes overlay) is active.
- SafetyGuard (password field, PIN/OTP/CVV words in English and Urdu, labels above inputs, custom PIN keypads) pauses before anything is uploaded.
- Numbers of 6+ digits become `[number]` on the phone and again on the server.
- Only `metrics.jsonl` (no screen text) is stored; the learning and cache files store label hashes, not labels. Debug snapshots are saved only when you press the button.
