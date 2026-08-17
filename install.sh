#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
TARGET=${1:-/home/ibmcmadm/cm-retention}

prompt_default() {
    local label=$1 default=$2 value
    read -r -p "$label [$default]: " value
    printf '%s' "${value:-$default}"
}

if [[ "$ROOT" != "$TARGET" ]]; then
    if [[ -e "$TARGET" ]]; then
        echo "ERROR: target already exists: $TARGET" >&2
        exit 2
    fi
    mkdir -p "$(dirname -- "$TARGET")"
    cp -a "$ROOT" "$TARGET"
fi

cd "$TARGET"

echo "CM Retention 0.2.0 installer"
echo

if [[ -t 0 && -t 1 ]]; then
    if [[ -f .env ]]; then
        echo "Existing configuration kept: $TARGET/.env"
        chmod 600 .env
    else
        ibmcmroot=$(prompt_default "IBM CM root" "/opt/IBM/db2cmv8")
        java_home=$(prompt_default "Java home" "/opt/IBM/WebSphere/AppServer/java/8.0")
        database=$(prompt_default "CM database" "LSDB")
        user=$(prompt_default "CM user" "icmadmin")
        read -r -s -p "CM password: " password
        echo
        [[ -n "$password" ]] || { echo "ERROR: password must not be empty" >&2; exit 2; }

        umask 077
        cat > .env <<ENV
CM_DATABASE=$database
CM_USER=$user
CM_PASSWORD=$password
IBMCMROOT=$ibmcmroot
JAVA_HOME=$java_home
ENV
        chmod 600 .env
        echo "[OK] configuration written (.env mode 600)"
    fi

    echo "Building..."
    ./build.sh
    echo
    echo "Testing installation..."
    bin/cm-retention status
    echo
    echo "Installed successfully: $TARGET"
    echo "Run: $TARGET/bin/cm-retention"
else
    if [[ ! -f .env ]]; then
        cp .env.example .env
        chmod 600 .env
    fi
    ./build.sh
    echo
    echo "Installed in: $TARGET"
    echo "Edit:         $TARGET/.env"
    echo "Test:         $TARGET/bin/cm-retention status"
fi
