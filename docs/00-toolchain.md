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
bash scripts/check_env.sh      # full verification, exits non-zero on failure
```

## Two failures worth remembering

**1. The Gradle wrapper download died mid-transfer.** `gradle-wrapper.properties`
shipped with `networkTimeout=10000` (10 s) — far too short for the 131 MB
distribution, so the stream was cut and surfaced as an obscure
`SSLTransport.decode` stack trace. Raised to `120000`. Workaround used at the
time: seed `~/.gradle/wrapper/dists/gradle-8.11.1-bin/<hash>/` with an
already-downloaded zip instead of re-fetching.

**2. The build was OOM-killed twice.** This box has 7.6 GB RAM; the browser
alone held 3.75 GB and the 512 MB swap was full, so the kernel killed the
Gradle daemon (`oom_score_adj:200` makes it the first target) during dex
merging. Two fixes, both required:

* `gradle.properties` — heap `-Xmx3g` → `-Xmx1500m`, `workers.max=1`,
  `parallel=false`, and `kotlin.daemon.jvmargs=-Xmx512m` (the Kotlin compile
  daemon is a *separate* JVM that can be killed independently).
* `sudo bash scripts/setup_swap.sh` — adds a 4 GiB `/swapfile-adveto` alongside
  the existing 512 MB `/swapfile` (never `swapoff`s the live one).

Check memory before a long build with `free -h`; if *available* is under
~1.5 GB, expect a kill.

## Build proof (Batch 1)

```
./gradlew assembleDebug → BUILD SUCCESSFUL (1m10s)
app/build/outputs/apk/debug/app-debug.apk  12.7 MB
  package   org.adveto.app.debug  versionName 0.1.0
  minSdk 29  targetSdk 35  compileSdk 35   label 'AdVeto'
```
