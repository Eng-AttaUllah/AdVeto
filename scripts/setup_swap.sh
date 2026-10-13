#!/usr/bin/env bash
# Adds a swapfile so Gradle/Android builds survive on this 7.6 GB box.
#
#   sudo bash scripts/setup_swap.sh          # 4 GiB (default)
#   sudo bash scripts/setup_swap.sh 8G       # custom size
#
# NOTE: this box already has a 512 MB /swapfile in active use. We deliberately
# do NOT touch it (swapoff-ing 511 MB of live pages into ~1 GB of free RAM can
# OOM the machine) — we add a second one instead.
#
# Idempotent: running it twice does nothing. Rollback:
#   sudo swapoff /swapfile-adveto && sudo rm /swapfile-adveto
#   (and delete the /swapfile-adveto line from /etc/fstab)
set -euo pipefail

SIZE="${1:-4G}"
FILE=/swapfile-adveto

if [ "$(id -u)" -ne 0 ]; then
    echo "This script must run as root:  sudo bash $0" >&2
    exit 1
fi

if swapon --show=NAME --noheadings | grep -qx "$FILE"; then
    echo "Already active:"
    swapon --show
    free -h | sed -n '1,3p'
    exit 0
fi

# Requested size in bytes (4G -> 4294967296); accept K/M/G suffix.
case "$SIZE" in
    *G) want=$(( ${SIZE%G} * 1024 * 1024 * 1024 )) ;;
    *M) want=$(( ${SIZE%M} * 1024 * 1024 )) ;;
    *K) want=$(( ${SIZE%K} * 1024 )) ;;
    *)  want="$SIZE" ;;
esac

echo "Creating ${SIZE} swapfile at ${FILE}..."
if [ -f "$FILE" ]; then
    current=$(stat -c%s "$FILE")
    if [ "$current" -lt "$want" ]; then
        echo "Existing file is $current bytes — resizing to ${SIZE}"
        fallocate -l "$SIZE" "$FILE"
    fi
else
    fallocate -l "$SIZE" "$FILE"
fi
chmod 600 "$FILE"
mkswap "$FILE" >/dev/null
swapon "$FILE"

# Persist across reboots.
if ! grep -qs "^${FILE}[[:space:]]" /etc/fstab; then
    printf '%s none swap sw 0 0\n' "$FILE" >> /etc/fstab
    echo "Added ${FILE} to /etc/fstab"
fi

echo
swapon --show
free -h | sed -n '1,3p'
