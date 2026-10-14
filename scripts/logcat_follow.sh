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
set -u

DUR="${1:-600}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${2:-$ROOT/docs/gate/logcat_$(date +%Y%m%d_%H%M%S).txt}"

mkdir -p "$(dirname "$OUT")"
END=$(( $(date +%s) + DUR ))

echo "following logcat for ${DUR}s -> $OUT"
echo "drop markers look like:  --- connection gap ---"

while [ "$(date +%s)" -lt "$END" ]; do
    if adb get-state >/dev/null 2>&1; then
        # Exit as soon as the transport dies, then fall through to retry.
        adb logcat -v time >> "$OUT" 2>&1
        echo "[$(date +%H:%M:%S)] --- connection gap (adb stream ended) ---" >> "$OUT"
    else
        echo "[$(date +%H:%M:%S)] --- connection gap (device not in 'device' state) ---" >> "$OUT"
    fi
    sleep 2
done

echo "done: $(wc -l < "$OUT") lines -> $OUT"
