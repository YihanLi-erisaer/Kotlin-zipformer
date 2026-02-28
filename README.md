# Kotlin-ASR-with-ncnn

Android Automatic Speech Recognition (ASR) app built with **Kotlin**, **Jetpack Compose**, and **sherpa-ncnn** using the **Zipformer** model architecture. The project runs on-device speech recognition powered by [ncnn](https://github.com/Tencent/ncnn) and [sherpa-ncnn](https://github.com/k2-fsa/sherpa-ncnn).

This repository is the local project structure for [Kotlin-zipformer](https://github.com/YihanLi-erisaer/Kotlin-zipformer).

---

## Features

- **On-device ASR** — No network required; all inference runs locally
- **Zipformer model** — Uses the Zipformer architecture via sherpa-ncnn
- **Modern Android stack** — Kotlin, Compose, Hilt, Clean Architecture
- **arm64-v8a** — Optimized for 64-bit Android devices

---

## Project Structure

```
Kotlin-ASR-with-ncnn/
├── app/                    # Main Android application
├── feature/home/           # ASR UI (Compose screen, ViewModel)
├── domain/                 # Use cases, models, repository interface
├── data/                   # ASR repository implementation
├── core/
│   ├── media/              # Native JNI bridge, AudioRecorder, ModelConfig
│   ├── ui/                 # Shared UI (theme, colors)
│   └── common/             # Shared utilities
├── sherpa-ncnn/            # sherpa-ncnn C++ library (build scripts)
├── scripts/                # Build script for native libs
└── gradle/
```

---

## Prerequisites

- **Android Studio** (Arctic Fox or newer)
- **Android NDK** (r22+)
- **Gradle** 8.x (via Android Gradle Plugin)
- **Kotlin** 2.0+
- **sherpa-ncnn** — Cloned into `sherpa-ncnn/` (see below)
- **Zipformer model assets** — encoder/decoder/joiner `.param`, `.bin`, and `tokens.txt`

---

## Setup

### 1. Clone the Repository

```bash
git clone https://github.com/YihanLi-erisaer/Kotlin-zipformer.git
cd Kotlin-zipformer
```

### 2. Add sherpa-ncnn

The `sherpa-ncnn` directory is excluded from the repo (see `.gitignore`). Clone it into the project root:

```bash
git clone https://github.com/k2-fsa/sherpa-ncnn.git sherpa-ncnn
```

### 3. Build Native Libraries

Set `ANDROID_NDK` to your NDK path, then run the build script:

```bash
# Unix / Git Bash / WSL
export ANDROID_NDK=/path/to/your/ndk
chmod +x scripts/build_sherpa_ncnn_and_copy_to_app.sh
./scripts/build_sherpa_ncnn_and_copy_to_app.sh
```

This builds sherpa-ncnn for `arm64-v8a`, merges static libs into `libsherpa-ncnn-core.a`, and copies them to `core/media/src/main/cpp/jniLibs/arm64-v8a/`.

**Manual build:** See [core/media/src/main/cpp/jniLibs/README.md](core/media/src/main/cpp/jniLibs/README.md) for step-by-step instructions.

### 4. Add Model Assets

Place your Zipformer model files under `core/media/src/main/assets/`:

| File           | Description            |
|----------------|------------------------|
| `encoder.param`| Encoder network config |
| `encoder.bin`  | Encoder weights        |
| `decoder.param`| Decoder network config |
| `decoder.bin`  | Decoder weights        |
| `joiner.param` | Joiner network config  |
| `joiner.bin`   | Joiner weights         |
| `tokens.txt`   | Vocabulary tokens      |

You can obtain compatible models from [sherpa-ncnn pretrained models](https://github.com/k2-fsa/sherpa-ncnn/releases).

### 5. ncnn Prebuilt (Optional)

The app prefers sherpa-ncnn’s built `libncnn.a` (non-Vulkan). If that’s not available, it falls back to prebuilt ncnn in `core/media/src/main/cpp/ncnn-20260113-android-vulkan/` for the `arm64-v8a` ABI.

---

## Building the App

```bash
./gradlew assembleDebug
```

Or open the project in Android Studio and run it on a device/emulator (arm64-v8a recommended).

---

## Architecture

- **UI:** Jetpack Compose (`ASRScreen`, Material 3)
- **State:** `ASRViewModel` + `Flow` for transcriptions
- **Domain:** `StartASRUseCase`, `StopASRUseCase`, `ASRRepository`
- **Data:** `ASRRepositoryImpl` — manages `NcnnNativeBridge` and `AudioRecorder`
- **Native:** JNI bridge in `kotlin_asr_with_ncnn.cpp` → sherpa-ncnn `Recognizer`

---

## Dependencies

- AndroidX Core, Lifecycle, Activity Compose
- Compose BOM, Material 3
- Hilt (dependency injection)
- sherpa-ncnn, ncnn, kaldi-native-fbank, kissfft (native)

---

## Troubleshooting

### SIGSEGV on startup in `initModelNative`

Rebuild `libsherpa-ncnn-core.a` with the `Model::Create` null-check fix in `sherpa-ncnn/csrc/recognizer.cc`, then run the build script again.

### Vulkan crashes on some devices

The app tries to use sherpa-ncnn’s non-Vulkan `libncnn.a` from `jniLibs` when available, which avoids Vulkan on problematic devices.

### Missing model assets

Ensure all 7 files (encoder, decoder, joiner param/bin + tokens.txt) exist under `core/media/src/main/assets/` and paths match the `ModelConfig` in `MainActivity.kt`.

---

## License

See the repository for license information.

---

## Related Projects

- [sherpa-ncnn](https://github.com/k2-fsa/sherpa-ncnn) — Speech recognition with ncnn
- [ncnn](https://github.com/Tencent/ncnn) — High-performance neural network framework
