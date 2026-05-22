# Market Making — Android

Native Kotlin/Compose port of the Python desktop app. Speech recognition is
**Vosk** (bundled small en-US model) with a tight trader vocabulary; quote
parsing is the deterministic `QuoteParser` (direct port of
`python/quote_parser.py`). TTS uses Android's system `TextToSpeech`.

## First-time setup

The Vosk speech model (~68 MB unpacked) is **gitignored** — fetch it after
cloning:

```sh
cd android
./fetch-vosk-model.sh
```

That writes `app/src/main/assets/vosk-model/` (and the `uuid` marker file
vosk-android needs). Skip this and the build still succeeds but the app will
crash on the first listen attempt.

## Build

Open `android/` in Android Studio. Sync should pull AGP 8.7, Kotlin 2.0.20,
Compose BoM 2024.10.00, plus `vosk-android` + `jna`. Min SDK 26, target SDK 35.

CLI build:

```sh
cd android
# one-time: generate gradle wrapper if you have system gradle ≥ 8.10
gradle wrapper --gradle-version 8.10.2
./gradlew assembleRelease
./gradlew installRelease    # signs with the debug keystore (dev-only)
```

## Contract server

The app fetches contracts from the HTTP server in `../server/`. Start it
first:

```sh
( cd ../server && cargo run --release )   # binds 0.0.0.0:7878
```

Open the app → Settings → enter the server URL the phone can reach
(`http://10.0.2.2:7878` for an emulator, host LAN IP for a physical device,
or a tailnet hostname). Saved per-device via SharedPreferences.

## Speech recognition

Two paths, picked in Settings:

- **Vosk (bundled, recommended)** — on-device, restricted-vocabulary grammar
  (numbers + trader keywords only — `at`, `bid`, `for`, `up`, `to`, `by`,
  `offered`, etc.). Works without GMS, no permissions beyond mic. Word
  timestamps drive automatic comma insertion at pauses, so a quote like
  *"four [pause] five ten up"* parses correctly as bid=4 / ask=5 / size=10.
- **System default / installed services** — `SpeechRecognizer` delegating to
  whatever app registers a `RecognitionService`. Useful if you have FUTO /
  Sayboard / Google STT installed and prefer those. Often broken on
  LineageOS without GMS.

The voice path is intentionally **quote-only**. Actions (skip / repeat /
quit) live on UI buttons, not in the speech grammar — this keeps Vosk's
vocabulary tight enough to recognise small numbers reliably.

## Layout

```
android/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/vosk-model/             # Vosk small en-US (~68 MB, gitignored — fetch via android/fetch-vosk-model.sh)
│       ├── java/com/alanxw/marketmaking/
│       │   ├── MainActivity.kt            # Compose UI
│       │   ├── SimViewModel.kt            # state machine, round loop
│       │   ├── ContractClient.kt          # HTTP fetch from server
│       │   ├── SettingsStore.kt           # SharedPreferences wrapper
│       │   ├── Sim.kt                     # grading + counterparty noise
│       │   ├── QuoteParser.kt             # port of python/quote_parser.py
│       │   ├── VoskRecognizer.kt          # bundled Vosk path
│       │   ├── VoiceInput.kt              # SpeechRecognizer path
│       │   ├── MicTest.kt                 # raw AudioRecord diagnostics
│       │   └── Speech.kt                  # TextToSpeech wrapper
│       └── res/values/{themes,strings}.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```
