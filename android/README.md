# Market Making — Android

Native Kotlin/Compose port of the Python desktop app. Uses Android's built-in
`SpeechRecognizer` (VAD + STT in one call) and `TextToSpeech` (system voice).
The deterministic `QuoteParser` is a direct port of `python/quote_parser.py`.
Google AI Edge / MediaPipe LLM Inference is wired in as the parser fallback.

## Build

Open `android/` in Android Studio. Sync should pull AGP 8.7, Kotlin 2.0.20,
Compose BoM 2024.10.00, and `com.google.mediapipe:tasks-genai`. Min SDK 26,
target SDK 35.

CLI build:

```sh
cd android
# one-time: generate gradle wrapper if you have system gradle ≥ 8.10
gradle wrapper
./gradlew assembleDebug
./gradlew installDebug   # or sideload the APK from app/build/outputs/apk
```

## Contract server

The app fetches contracts from the HTTP server in `../server/`. Start it
before launching the app:

```sh
( cd ../server && uv run python server.py )   # binds 0.0.0.0:7878
```

The Android client defaults to `http://10.0.2.2:7878` — emulator-friendly
(routes to host's localhost). For a physical device on the same wifi,
override the URL by editing `ContractClient.DEFAULT_URL` (or wire a settings
screen) to point at the host's LAN IP, e.g. `http://192.168.1.42:7878`.

## LLM fallback (optional)

`QuoteParser` handles essentially every common quote phrasing on-device with
no model. The LLM fallback only fires for inputs the deterministic parser
can't match. To enable it, push a Gemma `.task` model:

```sh
adb shell mkdir -p /data/local/tmp/llm
adb push gemma-3-1b-it-int4.task /data/local/tmp/llm/gemma.task
```

Get models from <https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference>.
If no model is present the app still works — it just re-prompts on unparseable
input rather than calling the LLM.

## Layout

```
android/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/alanxw/marketmaking/
│       │   ├── MainActivity.kt       # Compose UI
│       │   ├── SimViewModel.kt       # state machine, round loop
│       │   ├── ContractClient.kt     # HTTP fetch from server
│       │   ├── Sim.kt                # grading + counterparty noise
│       │   ├── QuoteParser.kt        # port of python/quote_parser.py
│       │   ├── VoiceInput.kt         # SpeechRecognizer wrapper
│       │   ├── Speech.kt             # TextToSpeech wrapper
│       │   └── LlmFallback.kt        # MediaPipe LLM Inference
│       └── res/values/{themes,strings}.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```
