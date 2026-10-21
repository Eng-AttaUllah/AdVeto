#!/usr/bin/env bash
# Logcat collector that survives USB drops.
#
#   bash scripts/logcat_follow.sh [seconds] [outfile]
#
# This box's link to the handset drops every few minutes (re-enumeration shows
# as `unauthorized` or `gone` in `adb devices`), and a plain `adb logcat` dies
# silently with the file truncated — which reads exactly like "the app stopped".
# This loop restarts the reader whenever the device returns and writes a
# separator marker, so an unobserved window is *visible as a gap* instead of
# masquerading as a crash.
#
# WHY THE THREE STATES ARE KEPT DISTINCT
# ---------------------------------------
# Conflating them is precisely how this tool failed twice before anyone
# noticed. `env.sh` owns the PATH entry for platform-tools; launched without
# it, `adb` is absent (exit 127) and the old version reported that as
# "device not in 'device' state" — ten minutes of markers written while the
# phone was demonstrably connected, in a file indistinguishable from real
# evidence. A collector that cannot tell "my tool is broken" from "the phone
# is unplugged" reproduces the exact failure mode it was written to fix.
#
#   --- collector start ---    header: when it began, and which adb it resolved
#   --- collector error ---    adb itself could not run (missing, broken, no server)
#   --- device absent ---      adb ran fine and listed no devices
#   --- device present ---     transport enumerated but not in `device` state
#   --- connection gap ---     the streaming reader ended mid-run
#
# The device serial is never written to the file; it stays redacted (proposal
# §8 / commit 3d5009a).
set -u

DUR="${1:-600}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${2:-$ROOT/docs/gate/logcat_$(date +%Y%m%d_%H%M%S).txt}"

# --- resolve adb ---------------------------------------------------------
# Never assume `adb` is on PATH. Try ADB=, then PATH, then what env.sh knows
# about, and refuse to start rather than emit a plausible-looking stream of
# markers that mean nothing.
ADB_BIN=""
if [ -n "${ADB:-}" ]; then
    ADB_BIN="$ADB"
elif command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
elif [ -f "$ROOT/env.sh" ]; then
    ADB_BIN="$(bash -c "source '$ROOT/env.sh' >/dev/null 2>&1; command -v adb" 2>/dev/null || true)"
fi

if [ -z "$ADB_BIN" ] || [ ! -x "$ADB_BIN" ]; then
    echo "FATAL: adb not found." >&2
    echo "  Run after:  source env.sh" >&2
    echo "  or set:     ADB=/path/to/adb" >&2
    exit 2
fi

if ! "$ADB_BIN" version >/dev/null 2>&1; then
    echo "FATAL: '$ADB_BIN' exists but cannot execute ('adb version' failed)." >&2
    exit 2
fi

mkdir -p "$(dirname "$OUT")"
END=$(( $(date +%s) + DUR ))

{
    echo "=== collector start $(date '+%Y-%m-%d %H:%M:%S') dur=${DUR}s ==="
    echo "=== adb: $ADB_BIN ($("$ADB_BIN" version 2>/dev/null | head -1 | tr -d '\r')) ==="
} >> "$OUT"

echo "following logcat for ${DUR}s -> $OUT"
echo "adb: $ADB_BIN"
echo "markers distinguish error / absent / present / stream-ended"

while [ "$(date +%s)" -lt "$END" ]; do
    # `devices`, not `get-state`: it succeeds while listing nothing, which is
    # the only reliable way to separate "unplugged" from "adb cannot run".
    devs=$("$ADB_BIN" devices 2>/dev/null)
    rc=$?

    if [ "$rc" -ne 0 ]; then
        echo "[$(date +%H:%M:%S)] --- collector error: adb devices exited $rc ---" >> "$OUT"
        sleep 3
        continue
    fi

    # Drop the "List of devices attached" header line before matching.
    list=$(printf '%s\n' "$devs" | sed '1d')

    if printf '%s\n' "$list" | grep -qE '^[[:alnum:]_.:-]+[[:space:]]+device$'; then
        # Reader exits as soon as the transport dies, then fall through to retry.
        "$ADB_BIN" logcat -v time >> "$OUT" 2>&1
        echo "[$(date +%H:%M:%S)] --- connection gap (adb stream ended) ---" >> "$OUT"
    elif printf '%s\n' "$list" | grep -qE '^[[:alnum:]_.:-]+[[:space:]]+(unauthorized|offline|recovery|sideload|authorizing)$'; then
        echo "[$(date +%H:%M:%S)] --- device present but not in device state ---" >> "$OUT"
    else
        echo "[$(date +%H:%M:%S)] --- device absent (adb ran, no devices listed) ---" >> "$OUT"
    fi
    sleep 2
done

echo "done: $(wc -l < "$OUT") lines -> $OUT"
