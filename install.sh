#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
TARGET=${1:-/home/ibmcmadm/cm-retention}

if [[ "$ROOT" != "$TARGET" ]]; then
    if [[ -e "$TARGET" ]]; then
        echo "ERROR: target already exists: $TARGET" >&2
        exit 2
    fi
    mkdir -p "$(dirname -- "$TARGET")"
    cp -a "$ROOT" "$TARGET"
fi

cd "$TARGET"
if [[ ! -f .env ]]; then
    cp .env.example .env
    chmod 600 .env
fi

./build.sh

echo
echo "Installed in: $TARGET"
echo "Edit:         $TARGET/.env"
echo "Test:         $TARGET/bin/cm-retention connection test"
