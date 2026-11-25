#!/usr/bin/env bash
#
# A-017 — device identity source gate (IVI-ACC-DSN-CR-005 D-011 / BLK-4).
#
# The car must never derive its device identity from a secure settings identifier: it is resettable,
# it collides across devices of the same model and it is not a factory identity. The only accepted
# sources are the unified security service (`DeviceSecurityStatus.deviceId`) and device level read
# only platform properties (`ro.serialno`, `Build.getSerial()`, `Build.SERIAL`).
#
# The scan covers every Kotlin/Java/XML source under the IVI-ACC modules and the demo app. Any hit
# fails the gate — including comments — so the identifier cannot creep back in as "just a fallback".
# Removal rationale and alternatives: CR-005 D-011.
#
# Usage: tools/check-forbidden-identity-source.sh [dir ...]
#        (defaults to the modules of this repository, build output excluded)

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR" || exit 2

if [ "$#" -gt 0 ]; then
    TARGETS=("$@")
else
    TARGETS=(acc-domain acc-data-cloud acc-data-local acc-data-sec acc-platform app)
fi

# The identifier is assembled so this script does not itself match a repository wide grep.
PATTERN="ANDROID""_ID"

existing=()
for target in "${TARGETS[@]}"; do
    if [ -d "$target" ]; then
        existing+=("$target")
    fi
done

if [ "${#existing[@]}" -eq 0 ]; then
    echo "A-017: no source directory found (looked in: ${TARGETS[*]})" >&2
    exit 2
fi

hits="$(grep -Rn \
    --include='*.kt' --include='*.java' --include='*.xml' \
    --exclude-dir=build --exclude-dir='.gradle' \
    -e "$PATTERN" "${existing[@]}" 2>/dev/null || true)"

if [ -n "$hits" ]; then
    echo "A-017 FAILED: a secure settings identifier must never be a device identity source."
    echo "(CR-005 D-011 / BLK-4; use the unified security service or a read only platform property)"
    echo
    echo "$hits"
    exit 1
fi

echo "A-017 OK: no forbidden device identity source in: ${existing[*]}"
exit 0
