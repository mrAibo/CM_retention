#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ENV_FILE=${CM_RETENTION_ENV:-${ROOT}/.env}

read_env_value() {
    local key=$1
    local file=$2
    [[ -f "$file" ]] || return 0
    awk -v wanted="$key" '
        /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
        {
            line=$0
            pos=index(line, "=")
            if (pos == 0) next
            key=substr(line, 1, pos-1)
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
            if (key != wanted) next
            value=substr(line, pos+1)
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
            if ((substr(value,1,1) == "\"" && substr(value,length(value),1) == "\"") ||
                (substr(value,1,1) == "\047" && substr(value,length(value),1) == "\047")) {
                value=substr(value,2,length(value)-2)
            }
            print value
            exit
        }
    ' "$file"
}

: "${IBMCMROOT:=$(read_env_value IBMCMROOT "$ENV_FILE")}" 
: "${JAVA_HOME:=$(read_env_value JAVA_HOME "$ENV_FILE")}" 
IBMCMROOT=${IBMCMROOT:-/opt/IBM/db2cmv8}
JAVA_HOME=${JAVA_HOME:-/opt/IBM/WebSphere/AppServer/java/8.0}

JAVAC=${JAVA_HOME}/bin/javac
JAR=${JAVA_HOME}/bin/jar

[[ -x "$JAVAC" ]] || { echo "ERROR: javac not found: $JAVAC" >&2; exit 2; }
[[ -x "$JAR" ]] || { echo "ERROR: jar not found: $JAR" >&2; exit 2; }
[[ -f "${IBMCMROOT}/lib/cmbicmsdk81.jar" ]] || {
    echo "ERROR: IBM CM SDK not found under ${IBMCMROOT}/lib" >&2
    exit 2
}

rm -rf "${ROOT}/build/classes"
mkdir -p "${ROOT}/build/classes"

"$JAVAC" \
    -encoding UTF-8 \
    -source 1.8 \
    -target 1.8 \
    -Xlint:all \
    -Xlint:-path \
    -cp "${IBMCMROOT}/cmgmt:${IBMCMROOT}/lib/*" \
    -d "${ROOT}/build/classes" \
    "${ROOT}/src/CmRetention.java"

cat > "${ROOT}/build/manifest.mf" <<MANIFEST
Manifest-Version: 1.0
Main-Class: CmRetention
Implementation-Title: cm-retention
Implementation-Version: 0.1.2
MANIFEST

"$JAR" cfm \
    "${ROOT}/build/cm-retention.jar" \
    "${ROOT}/build/manifest.mf" \
    -C "${ROOT}/build/classes" .

printf 'Built: %s\n' "${ROOT}/build/cm-retention.jar"
