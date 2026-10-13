#!/usr/bin/env bash
# Verify the AdVeto toolchain end to end. Run after `source env.sh`.
#
#   bash scripts/check_env.sh
#
# Exits non-zero if anything a Batch needs is missing, and prints what to fix.
set -u

fail=0
ok()   { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
miss() { printf '  \033[31mMISS\033[0m %s\n' "$1"; fail=1; }

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "AdVeto environment check — $root"

echo "[python]"
if [ -x "$root/.venv/bin/python" ]; then
    ok "venv: $("$root/.venv/bin/python" --version 2>&1)"
    "$root/.venv/bin/python" - <<'EOF' || fail=1
mods = ["tensorflow", "numpy", "pandas", "sklearn", "cv2", "PIL", "pytesseract"]
for m in mods:
    try:
        mod = __import__(m)
        print(f"  \033[32mOK\033[0m   {m} {getattr(mod, '__version__', '?')}")
    except Exception as e:
        print(f"  \033[31mMISS\033[0m {m}: {e}")
        raise SystemExit(1)
EOF
else
    miss "no .venv — run: uv venv --python 3.12 .venv && uv pip install -r requirements.txt"
fi

echo "[android]"
command -v java  >/dev/null && ok "java: $(java -version 2>&1 | head -1)" || miss "java (source env.sh)"
command -v adb   >/dev/null && ok "adb: $(adb --version 2>&1 | head -1)" || miss "adb (SDK platform-tools)"
command -v gradle >/dev/null && ok "gradle: $(gradle --version 2>/dev/null | grep '^Gradle')" || miss "gradle"
[ -f "$root/android/local.properties" ] && ok "local.properties present" || miss "local.properties (sdk.dir)"

echo "[tesseract — offline OCR for dataset prep]"
command -v tesseract >/dev/null && ok "tesseract $(tesseract --version 2>&1 | head -1 | cut -d' ' -f2)" || miss "tesseract"

echo
if [ "$fail" -eq 0 ]; then echo "All checks passed."; else echo "Some checks FAILED."; fi
exit "$fail"
