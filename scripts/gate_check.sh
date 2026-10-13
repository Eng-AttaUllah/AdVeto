#!/usr/bin/env bash
# Week 1-2 feasibility gate harness (proposal §9).
#
#   bash scripts/gate_check.sh specs     # record the stated test device
#   bash scripts/gate_check.sh install   # build must exist: install + launch
#   bash scripts/gate_check.sh watch 60  # capture pipeline logcat for 60 s
#
# Device results are written to docs/gate/ and become the evidence behind the
# written go/no-go decision.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/docs/gate"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
PKG="org.adveto.app.debug"
ACTIVITY="$PKG/.MainActivity"
# Debug build has applicationIdSuffix ".debug"; the release id has no suffix.

mkdir -p "$OUT"

need_adb() {
    if ! command -v adb >/dev/null 2>&1; then
        # shellcheck disable=SC1091
        source "$ROOT/env.sh"
    fi
    command -v adb >/dev/null 2>&1 || { echo "adb not found — run: source env.sh" >&2; exit 1; }

    if [ "$(adb devices | sed -n '2,$p' | grep -cw 'device$')" -eq 0 ]; then
        echo "No device in 'device' state. Connect the handset and enable USB debugging." >&2
        echo "Current:" >&2
        adb devices >&2
        exit 1
    fi
}

prop() { adb shell getprop "$1" | tr -d '\r'; }

specs() {
    need_adb
    local f="$OUT/device_specs.txt"
    {
        echo "AdVeto — stated test device (proposal §10)"
        echo "recorded: $(date -Iseconds)"
        echo
        echo "serial          = $(adb get-serialno | tr -d '\r')"
        echo "manufacturer    = $(prop ro.product.manufacturer)"
        echo "model           = $(prop ro.product.model)"
        echo "board/platform  = $(prop ro.board.platform)"
        echo "hardware        = $(prop ro.hardware)"
        echo "android release = $(prop ro.build.version.release)"
        echo "sdk int         = $(prop ro.build.version.sdk)"
        echo "abi             = $(prop ro.product.cpu.abilist)"
        echo "total ram       = $(adb shell grep MemTotal /proc/meminfo | tr -d '\r')"
        echo "density         = $(adb shell wm density | tr -d '\r')"
        echo "resolution      = $(adb shell wm size | tr -d '\r')"
        echo
        echo "proposal target = mid-range handset, 6 GB RAM, Snapdragon 6-series"
        echo "                 class chipset (or equivalent), Android 12"
    } | tee "$f"
    echo
    echo "-> $f"
}

install() {
    need_adb
    [ -f "$APK" ] || { echo "No APK at $APK — run: cd android && ./gradlew assembleDebug" >&2; exit 1; }

    echo "Installing $(basename "$APK") ($(du -h "$APK" | cut -f1))..."
    adb install -r "$APK"
    echo "Launching $ACTIVITY"
    adb shell am start -n "$ACTIVITY" >/dev/null

    cat <<'GUIDE'

Gate checklist — perform on the handset, then record the result:

  GO-1  Capture authorised and sustained
        In the app: [Start capture] -> accept the consent dialog.
        PASS if: the notification appears, [watch] shows `running (authorised)`
        and frames keep arriving at ~1 Hz for >= 60 s.
        FAIL if: consent cannot be obtained, or capture stops on its own
        (a deliberate user revoke is not a fail — it must show as `revoked`).

  GO-2  Advertisement region localised within the capture window
        Open an ad-heavy free app with a banner visible.
        PASS if: `regions >= 1` on frames taken while the ad is on screen.
        FAIL if: regions are always 0, or detection is so slow the ad has gone
        before the frame is processed.

  GO-3  Overlay draws over another application's advertisement
        In the app: [Allow overlay], grant it, return, then [Test overlay]
        while a third-party app with a banner is in the foreground.
        PASS if: the cover visibly paints over the ad band for 2.5 s.
        FAIL if: nothing draws (logcat: `SYSTEM_ALERT_WINDOW not granted` or
        `overlay failed`) -> contingency: demonstrate overlay inside the
        prototype's own test activity and record scope reduction.

  Record PASS/FAIL for each in docs/gate/gate_result.md.
  Contingency (proposal §9): if GO-1 or GO-3 fails on this Android version,
  scope reduces to detection + logging, overlay demonstrated in-prototype.

GUIDE
}

watch() {
    need_adb
    local seconds="${1:-60}"
    local f="$OUT/logcat_$(date +%Y%m%d_%H%M%S).txt"
    echo "Logging $seconds s of pipeline output -> $f"
    echo "(operate the phone now: start capture, open an ad-heavy app)"
    adb logcat -c
    timeout "$seconds" adb logcat -v time \
        AdVetoCapture:V AdVetoUI:V AdVetoOverlay:V AdVetoLog:V AdVetoPipeline:V "*:S" \
        2>/dev/null | tee "$f" || true
    echo
    echo "-> $f"
    echo "frames:   $(grep -c 'regions=' "$f" || true)"
    echo "overlays: $(grep -ci 'overlay' "$f" || true)"
}

case "${1:-}" in
    specs)   specs ;;
    install) install ;;
    watch)   shift; watch "${1:-60}" ;;
    *)
        echo "usage: $0 {specs|install|watch [seconds]}" >&2
        exit 1
        ;;
esac
