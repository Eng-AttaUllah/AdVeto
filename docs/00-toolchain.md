# AdVeto — toolchain bootstrap notes (Batch 1)

Everything is installed **user-space, no root** (`sudo` needs an interactive
password on this machine, so nothing was installed with `apt`).

## Locations

| Tool | Path | Version |
|---|---|---|
| Python (training) | `Projects/AdVeto/.venv` | 3.12.15 (via `uv`) |
| JDK | `~/opt/jdk-17.0.20.1+1` | Temurin 17.0.20.1 |
| Android SDK | `~/opt/android-sdk` | cmdline-tools 13114758, platform 35, build-tools 35.0.0, platform-tools (adb) |
| Gradle | `~/opt/gradle-8.11.1` | 8.11.1 (project wrapper pinned to the same version) |
| uv | `~/.local/uv` | 0.13.0 |
| tesseract | `/usr/bin/tesseract` | system package, used for offline OCR during dataset prep |

Archives kept in `~/opt/dl/` (JDK, cmdline-tools, Gradle) — deletable once verified.

## Environment (put this in a fresh shell or a `env.sh`)

```sh
export JAVA_HOME=$HOME/opt/jdk-17.0.20.1+1
export ANDROID_HOME=$HOME/opt/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$HOME/opt/gradle-8.11.1/bin:$HOME/.local/bin:$PATH"
```

`android/local.properties` already contains `sdk.dir=...` (git-ignored).

## Build commands

```sh
cd android
./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # requires a device with USB debugging (adb)
```

## Python

```sh
cd Projects/AdVeto
source .venv/bin/activate
python -c "import tensorflow as tf; print(tf.__version__)"
```
