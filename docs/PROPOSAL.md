# Pointly: an AI guide that shows first-time smartphone users where to tap

**Category:** AI for social good / accessibility / digital inclusion
**Platform:** Android app (Flutter + Kotlin) with a FastAPI backend on Google Gemini
**Status:** Working MVP, tested on an Android emulator and on a real OPPO phone with live Gemini
**Final:** 24 October 2026

> *"Pointly" is the working product name and may change before launch.*

---

## 1. Summary

Millions of people in Pakistan now own a smartphone but cannot use it on their own. They include older parents, people who are new to phones, people who read little or no English, and anyone who finds today's apps confusing. To send a photo, change a setting or top up their balance, they hand the phone to a child or a shopkeeper. That costs them independence and privacy, and it exposes them to fraud.

**Pointly is a patient guide that lives on the phone.** The user holds a small floating bubble and says what they want ("Send a photo to Sara on WhatsApp", "Make the text bigger"). Pointly reads the labels on the current screen, asks an AI model for **the one next thing to tap**, and shows it with an animated hand, a ring, a short caption and a spoken sentence. Then it waits. **The user always taps themselves.** When the screen changes, Pointly checks what happened and shows the next step. It keeps going until the task is done.

Pointly works in plain **English by default**. **Urdu** (Urdu script, right-to-left screens, Urdu voice) is one tap away.

## 2. The problem

- **Phones are built for people who already know phones.** Icons, menus hidden under ⋮, and settings buried three screens deep assume experience that first-time users don't have.
- **Help today means giving the phone away.** A family member or a shop assistant takes the phone and does the task. The user learns nothing and shares private chats, photos and sometimes their PIN.
- **Video tutorials and manuals don't match the screen in front of you.** Every phone brand (Samsung, OPPO, Xiaomi, Vivo…) names and arranges settings differently, and apps change all the time.
- **Existing assistants do the task for you.** Voice assistants and automation tools act on the user's behalf. That is risky for money and messages, and the user never learns the task.

## 3. Our solution

Pointly teaches by pointing, the way a patient relative would sit next to you and say "press this one".

| What the user sees | What happens |
| --- | --- |
| A green bubble at the edge of the screen, on top of every app | Hold it and speak, or tap it for five quick tasks and a text box |
| An animated hand that flies to the right button, with a pulsing ring | The AI chose this element from the screen's labels |
| A large caption, e.g. *"Tap the green WhatsApp icon"* | The same sentence is spoken aloud |
| Pointly waits | The user taps; Pointly checks the result and moves to the next step |
| *"Well done! All finished."* | The task is complete |

**It adapts to the user's mistakes.** If the user taps something else, Pointly says *"No problem, let's carry on from here"* and re-plans from the new screen. If they tap near the target but miss, it says *"Not there. Tap where the hand is pointing."*

**Help fades as the user learns.** The first time, the user gets the full hand, caption and voice. After a couple of successful runs, Pointly gives a short hint with no hand. Later it stays silent and only shows the hand if the user hesitates. The goal is that the user no longer needs Pointly.

**It handles real interfaces.** Pointly can guide taps, typing, scrolling a long list to reveal a hidden option, sliders (a hand that drags in the right direction), and the Back and Home buttons.

## 4. Safety and privacy by design

Safety is built into the architecture. These rules hold everywhere in the code:

1. **Pointly never acts for the user.** The Android accessibility service is declared read-only. It cannot tap, type, swipe or open apps, and a check script (`tools/check_no_actions.sh`) fails the build if any such API appears.
2. **No screenshots, ever.** Pointly reads only the text labels and positions of on-screen elements from the accessibility tree. No images are captured or uploaded.
3. **Stops at PINs and passwords.** A SafetyGuard runs on the phone *before anything is uploaded*. On password fields, PIN/OTP/CVV labels (English and Urdu) or custom PIN keypads, Pointly pauses and says *"Please fill in this screen yourself. I don't read it."*
4. **Sensitive numbers are masked.** Any number of 6 or more digits (phone, CNIC, account numbers) becomes `[number]` on the phone and again on the server.
5. **Reads only when asked.** Screen events are ignored unless a guidance session is active.
6. **Stores no screen text.** The metrics log keeps timings only. The learning and cache files store hashed labels, never the labels themselves.
7. **One-tap stop.** The × on the bubble or "Stop" in the notification removes everything instantly.
8. **Money and messages stay with the user.** For payments and PINs, the AI is instructed to stop and hand over: *"Please type your PIN yourself."*

## 5. How it works

```
 Phone (Kotlin core)                                   Backend (FastAPI)
 ───────────────────                                   ─────────────────
 Bubble: voice or typed goal
        │
 Read accessibility tree ──► SafetyGuard ──► Mask numbers ──►  POST /v1/plan
 (labels, bounds, sliders)    (pause on PIN)  (6+ digits)        │
                                                                 ▼
                                                     Gemini (structured JSON):
                                                     action, element_id,
                                                     say_en, say_ur, confidence
                                                                 │
                                                     Validate ids, length, script;
                                                     one corrective retry;
                                                     fallback model on 429/5xx
        ◄────────────────────────────────────────────────────────┘
 Overlay: hand + ring + caption + voice
        │
 User taps ──► StepVerifier (tap / type / scroll / drag / back / home)
        │          ├─ right element → next step
        │          ├─ other element → "No problem…" → re-plan
        │          └─ missed nearby → "Not there…"
        ▼
 Done → steps cached, learning level updated
```

**Key components**

- **Screen reader** turns the accessibility tree into a compact list of interactive elements (text, description, hint, class, bounds, slider range), ordered top to bottom. A typical read takes 20–60 ms.
- **Planner (Gemini).** The backend sends the goal, the masked screen and the recent step history, and asks for **one** next action as strict JSON. `gemini-3.5-flash-lite` is the main model and `gemini-3.6-flash` is the fallback, with a 60-second cooldown when the main model is overloaded.
- **Output validation.** The backend checks that the element exists and that the action fits it (e.g. "drag" only on sliders). It also checks that sentences are short and in the right script: plain English in `say_en`, real Urdu script in `say_ur`. A bad answer gets one corrective retry with the exact error.
- **Settle detector and step verifier.** Pointly waits until the screen stops changing (500 ms quiet, 2.5 s cap). Then it decides whether the user did the step, did something else, or the list just moved.
- **Plan cache.** Steps from finished tasks are reused when the same screen appears again, so repeated tasks skip the network. In testing, a repeated task needed 11 backend calls instead of 15, and cached steps showed the pointer in about 30 ms.
- **Learning store.** Per task and per screen, help moves from FULL to HINT to SILENT as the user succeeds.
- **Voice.** Speech recognition and text-to-speech use the phone's own engines first (`en-US` or `ur-PK`). If those are missing, Pointly uses cloud speech through the backend (`/v1/stt`, `/v1/tts`). Captions always carry the instruction, so Pointly works on mute too.

## 6. Use of AI

| Where | Model / technique | Why |
| --- | --- | --- |
| Next-step planning | Gemini flash-lite with a JSON schema, few-shot prompt and rules | Understands any app and any phone brand from labels alone. No per-app scripts. |
| Robustness | Validator, corrective retry, fallback model, time budget (6.5 s) | Elderly users can't wait or recover from bad output |
| Speech-to-text | Gemini audio understanding (cloud path) | Works for English, Urdu and mixed speech |
| Bilingual instructions | One answer returns both `say_en` and `say_ur` | Instant language switching with no extra call |
| Evaluation | Offline eval harness over labelled screen snapshots | Measures step accuracy, invalid-output rate and latency for every prompt or model change |

The AI never sees images. It sees a short list like `#4 "Network & internet" button [0,410,1080,560]`. This keeps requests small, fast and cheap, and protects privacy.

## 7. What we built and how we tested it

**Codebase:** Flutter app screens (onboarding with a language choice, permissions, home, settings, metrics), a Kotlin core (accessibility service, overlays, guidance loop, verifier, cache, learning, voice, metrics), and a FastAPI backend (planner, STT, TTS, mock provider, eval harness).

**Automated tests (all passing):**

| Suite | Tests |
| --- | --- |
| Backend (planner, validation, masking, auth, voice, eval, mock) | 73 |
| Kotlin core (guidance session, verifier, scroll/drag, cache, learning, masking, SafetyGuard, ordering, client) | 107 |
| Flutter (all five screens, language switching, quick tasks) | 21 |
| Safety check: no action / gesture / screenshot APIs | PASS |

**Emulator acceptance run (A1–A11): all passed.** The run covered the bubble over other apps, pointer alignment, full task guidance (typed, quick-task and spoken goals), recovery from wrong taps, help fading (FULL → HINT → SILENT across five runs), the PIN pause with no upload, instant cancel, network failure and retry, the cache hit on repeat, and a metrics export with no screen text.

**Live Gemini evaluation:** 9 of 9 labelled screens correct, median latency about 1.3 s per step (p90 1.7 s).

**Real phone (OPPO CPH2001, ColorOS 11, live Gemini):** Pointly guided a real user through *Display & brightness → scroll → Font & display size → font slider* on a brand-specific interface it had never seen. Each step took 1.2–2.4 s. The test found four real-world bugs, all fixed with new tests: runaway scroll detection, needless re-plans, sliders that could not be guided, and sliders matched by position.

## 8. Impact

- **Independence and dignity.** Users do their own tasks and learn them, instead of handing the phone over.
- **Fraud prevention.** Pointly never touches PIN screens and stops before payments, so users never need to share their PIN or OTP with a helper.
- **Works with any app.** Pointly isn't tied to WhatsApp or a specific bank. It reads whatever is on screen, so it already works with Settings, the dialer, WhatsApp-style chat lists and the launcher, across phone brands.
- **Local language.** English, plus Urdu script and voice for people who are more comfortable in Urdu. The design makes more languages (Punjabi, Sindhi, Pashto) a matter of adding one more output field.

**Who it is for:** older adults, first-time smartphone owners, people with low literacy, and the families, NGOs, banks and telcos who want them to use digital services safely.

## 9. Feasibility and cost

- **Cheap to run.** Each step sends a few hundred tokens of text (no images) to a flash-lite model. The plan cache and help fading cut calls further for repeated tasks.
- **Fast enough.** The median is about 1.3 s per step on home Wi-Fi. The phone keeps the user informed ("One moment, let me look") and retries once if the network is slow.
- **No root, no special phone.** It is a standard Android app using the official accessibility API in read-only mode.
- **Easy to deploy.** The backend is one FastAPI service. The app talks to it with an app key, and the AI provider key never leaves the server.

## 10. Roadmap

| Phase | Plan |
| --- | --- |
| After the final | Pilot with 20–30 older users through family members and a community centre. Measure task success and how quickly help fades. |
| Next 3 months | 50+ labelled screens across WhatsApp, banking/wallet apps and Settings. More languages (Punjabi, Sindhi, Pashto). On-device Urdu voice packs. |
| Next 6 months | Partnerships with telcos and wallet providers for guided onboarding inside their apps. A "family helper" mode where a relative records a task once for the user to follow. |
| Longer term | An on-device model for common screens (offline, even more private), plus iOS research. |

## 11. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| AI points at the wrong element | Strict output validation, corrective retry, the user always taps, a wrong turn triggers a re-plan, and an eval harness gates prompt and model changes |
| Privacy concerns about screen reading | Read-only, labels only, no screenshots, PIN pause before upload, masking, no screen text stored |
| Apps without good labels | Pointly falls back to position and class. We will publish findings to encourage accessible labelling. |
| Network or AI outage | Fallback model, cooldown, single retry, clear spoken error, tap-to-retry, and a cache for known tasks |
| Accessibility permission friction on Android 13+ | Step-by-step permission screen with the "Allow restricted settings" path explained |

## 12. Team

*[Team name, members, roles and contact details to be added.]*

---

## 13. How to test the project

You need a Windows/macOS/Linux laptop with Python 3.11+, and an Android phone (Android 11+) or emulator. Flutter 3.44+ is only needed to build from source; a release APK can be provided.

### A. Start the backend (5 minutes)

```bash
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt      # macOS/Linux: .venv/bin/pip
cp .env.example .env
```

Edit `.env`: set `GEMINI_API_KEY` and choose any `REHNUMA_APP_KEY` (for example `demo-key`).
**No Gemini key?** Set `LLM_PROVIDER=mock` to rehearse the full loop with a keyword planner.

```bash
.venv/Scripts/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Open `http://localhost:8000/v1/health`. It should show `{"status":"ok","model":"gemini-3.5-flash-lite"}`.

### B. Install and set up the app

```bash
cd app
flutter build apk --release
adb install -r build/app/outputs/flutter-apk/app-release.apk
adb reverse tcp:8000 tcp:8000        # phone on USB can reach the laptop at 127.0.0.1:8000
```

On the phone:

1. Open **Pointly**. Pick **English** (or **اردو**) and swipe through the three pages.
2. **Permissions:** turn on the Pointly accessibility service. If the switch is greyed out, go to *App info → ⋮ → Allow restricted settings*. Then allow the microphone and notifications.
3. **Settings:** set *Backend address* to `http://127.0.0.1:8000` (or `http://<laptop-ip>:8000` on the same Wi-Fi) and the *App key*. Tap **Check server**. It should say *Server OK*.
4. **Home:** tap **Start Pointly**. The green bubble appears.

### C. Try it (about 5 minutes)

| Test | What to do | Expected |
| --- | --- | --- |
| Guided task | Tap the bubble → **Make the text on my phone bigger** (or hold the bubble and say it) | The hand points at Settings, then Display, then Font size, then shows the slider drag. Each step is spoken and captioned. **You** tap every time. |
| Wrong tap | During a task, tap something else | *"No problem, let's carry on from here"*, then guidance continues from the new screen |
| Never acts | Watch the screen | Pointly never taps or scrolls by itself; nothing happens until you act |
| PIN safety | Go to *Settings → Security → Screen lock → PIN* during a task | Lock icon on the bubble and *"Please fill in this screen yourself…"*. No pointer, and no request is sent for that screen. |
| Cancel | Tap × on the bubble or **Stop** in the notification | Everything disappears at once |
| Offline | Stop the backend mid-task | *"The internet is slow, trying again"*, then an error with a retry option. Restart the backend and tap the bubble to continue. |
| Learning | Turn Demo mode off, then repeat the font-size task 3–4 times | Help fades from full hand → short hint → silent |
| Urdu | Settings → Language → **اردو** | All screens switch to Urdu (right-to-left); instructions are captioned and spoken in Urdu |
| Metrics | Home → Metrics | Sessions, success rate, planner latency and cache hit rate. Export contains no screen text. |

### D. Run the automated checks

```bash
cd backend && .venv/Scripts/python -m pytest                      # 73 tests
cd backend && .venv/Scripts/python -m eval.eval_planner           # live accuracy + latency on 9 screens
cd app && flutter test                                            # 21 tests
cd app/android && ./gradlew :app:testDebugUnitTest                # 107 tests
bash tools/check_no_actions.sh                                    # proves no tap/gesture/screenshot APIs
```
