#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ENV_FILE=${CM_RETENTION_ENV:-${ROOT}/.env}
BUILD_DIR=${ROOT}/build

read_env_value() {
    local key=$1 file=$2
    [[ -f "$file" ]] || return 0
    awk -v wanted="$key" '
        /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
        {
            pos=index($0, "="); if (pos == 0) next
            key=substr($0, 1, pos-1); gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
            if (key != wanted) next
            value=substr($0, pos+1); gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
            if ((substr(value,1,1) == "\"" && substr(value,length(value),1) == "\"") ||
                (substr(value,1,1) == "\047" && substr(value,length(value),1) == "\047")) {
                value=substr(value,2,length(value)-2)
            }
            print value; exit
        }
    ' "$file"
}

APP_VERSION=$(sed -n 's/^[[:space:]]*static final String VERSION = "\([^"]*\)";[[:space:]]*$/\1/p' "${ROOT}/src/CmRetention.java" | head -n 1)
if [[ -z "$APP_VERSION" ]]; then
    echo "ERROR: unable to determine application version from src/CmRetention.java" >&2
    exit 2
fi

: "${IBMCMROOT:=$(read_env_value IBMCMROOT "$ENV_FILE")}" 
: "${JAVA_HOME:=$(read_env_value JAVA_HOME "$ENV_FILE")}" 
IBMCMROOT=${IBMCMROOT:-/opt/IBM/db2cmv8}
JAVA_HOME=${JAVA_HOME:-/opt/IBM/WebSphere/AppServer/java/8.0}

JAVAC=${JAVA_HOME}/bin/javac
JAR=${JAVA_HOME}/bin/jar
[[ -x "$JAVAC" ]] || { echo "ERROR: javac not found: $JAVAC" >&2; exit 2; }
[[ -x "$JAR" ]] || { echo "ERROR: jar not found: $JAR" >&2; exit 2; }
[[ -f "${IBMCMROOT}/lib/cmbicmsdk81.jar" ]] || { echo "ERROR: IBM CM SDK not found under ${IBMCMROOT}/lib" >&2; exit 2; }
command -v tar >/dev/null 2>&1 || { echo "ERROR: tar is required to create the runtime package" >&2; exit 2; }

rm -rf "${BUILD_DIR}/classes" "${BUILD_DIR}/runtime"
mkdir -p "${BUILD_DIR}/classes"

"$JAVAC" \
    -encoding UTF-8 \
    -source 1.8 \
    -target 1.8 \
    -Xlint:all \
    -Xlint:-path \
    -cp "${IBMCMROOT}/cmgmt:${IBMCMROOT}/lib/*" \
    -d "${BUILD_DIR}/classes" \
    "${ROOT}/src/"*.java

cat > "${BUILD_DIR}/manifest.mf" <<MANIFEST
Manifest-Version: 1.0
Main-Class: CmRetention
Implementation-Title: cm-retention
Implementation-Version: ${APP_VERSION}
MANIFEST

VERSIONED_JAR="${BUILD_DIR}/cm-retention-${APP_VERSION}.jar"
CURRENT_JAR="${BUILD_DIR}/cm-retention.jar"

"$JAR" cfm "$VERSIONED_JAR" "${BUILD_DIR}/manifest.mf" -C "${BUILD_DIR}/classes" .
cp -f "$VERSIONED_JAR" "$CURRENT_JAR"
printf '%s\n' "$APP_VERSION" > "${BUILD_DIR}/.version"

# Build a transportable runtime bundle.  It intentionally contains no IBM SDK
# or credentials; those are supplied by the target IBM CM installation.
RUNTIME_NAME="cm-retention-${APP_VERSION}"
RUNTIME_STAGE="${BUILD_DIR}/runtime/${RUNTIME_NAME}"
RUNTIME_TAR="${BUILD_DIR}/${RUNTIME_NAME}-runtime.tar.gz"

mkdir -p "${RUNTIME_STAGE}/bin" "${RUNTIME_STAGE}/build" "${RUNTIME_STAGE}/docs"
cp "${ROOT}/bin/cm-retention" "${RUNTIME_STAGE}/bin/cm-retention"
chmod 755 "${RUNTIME_STAGE}/bin/cm-retention"
cp "$CURRENT_JAR" "${RUNTIME_STAGE}/build/cm-retention.jar"
cp "$VERSIONED_JAR" "${RUNTIME_STAGE}/build/"
cp "${BUILD_DIR}/.version" "${RUNTIME_STAGE}/build/.version"
cp "${ROOT}/.env.example" "${RUNTIME_STAGE}/.env.example"
cp "${ROOT}/README.md" "${ROOT}/DOKUMENTATION.md" "${ROOT}/CHANGELOG.md" "${RUNTIME_STAGE}/"
cp "${ROOT}/docs/"*.md "${RUNTIME_STAGE}/docs/"

cat > "${RUNTIME_STAGE}/INSTALL_RUNTIME.txt" <<EOF
CM Retention ${APP_VERSION} - precompiled runtime installation

This package was compiled against the IBM Content Manager SDK available on
its build host. The target host still needs a compatible IBM CM 8.7 runtime
and Java 8, but it does NOT need Git or javac.

Installation:

  1. Extract this archive as the intended runtime user (normally ibmcmadm).
  2. Enter the extracted ${RUNTIME_NAME} directory.
  3. Create configuration:
       cp .env.example .env
       chmod 600 .env
       vi .env
  4. Verify the packaged version:
       cat build/.version
       bin/cm-retention version
  5. Verify the target IBM CM environment:
       bin/cm-retention doctor
       bin/cm-retention status
  6. Start with read-only commands or --dry-run before a real write.

For separate TEST/PROD targets use separate .env.test/.env.prod files and
invoke them explicitly with --env.
EOF

rm -f "$RUNTIME_TAR"
tar -C "${BUILD_DIR}/runtime" -czf "$RUNTIME_TAR" "$RUNTIME_NAME"

CHECKSUM_FILE="${BUILD_DIR}/SHA256SUMS-${APP_VERSION}"
if command -v sha256sum >/dev/null 2>&1; then
    (
        cd "$BUILD_DIR"
        sha256sum \
            "cm-retention-${APP_VERSION}.jar" \
            "cm-retention-${APP_VERSION}-runtime.tar.gz" \
            > "$(basename "$CHECKSUM_FILE")"
    )
else
    rm -f "$CHECKSUM_FILE"
fi

printf 'Built version:  %s\n' "$APP_VERSION"
printf 'Runtime JAR:    %s\n' "$CURRENT_JAR"
printf 'Versioned JAR:  %s\n' "$VERSIONED_JAR"
printf 'Version file:   %s\n' "${BUILD_DIR}/.version"
printf 'Runtime bundle: %s\n' "$RUNTIME_TAR"
if [[ -f "$CHECKSUM_FILE" ]]; then
    printf 'Checksums:      %s\n' "$CHECKSUM_FILE"
fi
