#!/usr/bin/env bash
# AdVeto development environment — source this before building:
#
#   source ~/Projects/AdVeto/env.sh
#
# Everything here is user-space (no root was available for apt installs).

_adveto_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export JAVA_HOME="$HOME/opt/jdk-17.0.20.1+1"
export ANDROID_HOME="$HOME/opt/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$HOME/opt/gradle-8.11.1/bin:$HOME/.local/bin:$PATH"

adveto_env() {
    echo "JAVA_HOME  = $JAVA_HOME"
    echo "ANDROID_HOME= $ANDROID_HOME"
    echo "gradle      = $(command -v gradle || echo MISSING)"
    echo "adb         = $(command -v adb || echo MISSING)"
    echo "sdkmanager  = $(command -v sdkmanager || echo MISSING)"
    java -version 2>&1 | head -1
    if [ -d "$_adveto_root/.venv" ]; then
        echo "venv        = $_adveto_root/.venv ($("$_adveto_root/.venv/bin/python" --version 2>&1))"
    fi
}

adveto_env
