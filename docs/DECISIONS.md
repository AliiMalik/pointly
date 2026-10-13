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

## Open questions (SPEC 15), to answer on the demo phone

1. Wallet app for the PIN demo, and whether it exposes labels: _pending_
2. `SpeechRecognizer` `ur-PK` quality on the demo phone: _pending_
3. On-device Urdu TTS voice installed: _pending_
4. Measured planner latency over a venue-like network: _pending_
5. Where the backend runs during the demo: _pending_
