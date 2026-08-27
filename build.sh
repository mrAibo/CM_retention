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

JAVA=${JAVA_HOME}/bin/java
JAVAC=${JAVA_HOME}/bin/javac
JAR=${JAVA_HOME}/bin/jar
[[ -x "$JAVA" ]] || { echo "ERROR: java not found: $JAVA" >&2; exit 2; }
[[ -x "$JAVAC" ]] || { echo "ERROR: javac not found: $JAVAC" >&2; exit 2; }
[[ -x "$JAR" ]] || { echo "ERROR: jar not found: $JAR" >&2; exit 2; }
[[ -f "${IBMCMROOT}/lib/cmbicmsdk81.jar" ]] || { echo "ERROR: IBM CM SDK not found under ${IBMCMROOT}/lib" >&2; exit 2; }
[[ -f "${ROOT}/ret-policy.properties" ]] || { echo "ERROR: missing ret-policy.properties" >&2; exit 2; }
[[ -d "${ROOT}/profiles" ]] || { echo "ERROR: missing profiles directory" >&2; exit 2; }
[[ -f "${ROOT}/tests/selftest.sh" ]] || { echo "ERROR: missing tests/selftest.sh" >&2; exit 2; }
command -v tar >/dev/null 2>&1 || { echo "ERROR: tar is required to create the runtime package" >&2; exit 2; }

rm -rf "${BUILD_DIR}/classes" "${BUILD_DIR}/runtime" "${BUILD_DIR}/profiles"
mkdir -p "${BUILD_DIR}/classes"

# Keep useful Java warnings enabled, but suppress auxiliaryclass only. The
# project intentionally groups a few package-private helper classes in source
# files; javac otherwise emits the same harmless warning for every cross-file use.
"$JAVAC" \
    -encoding UTF-8 \
    -source 1.8 \
    -target 1.8 \
    -Xlint:all \
    -Xlint:-path \
    -Xlint:-auxiliaryclass \
    -cp "${IBMCMROOT}/cmgmt:${IBMCMROOT}/lib/*" \
    -d "${BUILD_DIR}/classes" \
    "${ROOT}/src/"*.java

# Pure regression checks. They load SDK classes but do not connect to CM or DB2.
echo "Running self-test..."
SELFTEST_CP="${BUILD_DIR}/classes:${IBMCMROOT}/cmgmt:${IBMCMROOT}/lib/*"
LD_LIBRARY_PATH="${IBMCMROOT}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}" \
    "$JAVA" -cp "$SELFTEST_CP" SelfTestMain

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
cp -f "${ROOT}/ret-policy.properties" "${BUILD_DIR}/ret-policy.properties"
mkdir -p "${BUILD_DIR}/profiles"
cp -f "${ROOT}/profiles/"*.properties "${BUILD_DIR}/profiles/"

# Always produce an environment example in build/. This makes source copies
# made without hidden dotfiles buildable as well (common with SCP/manual copy).
BUILD_ENV_EXAMPLE="${BUILD_DIR}/.env.example"
if [[ -f "${ROOT}/.env.example" ]]; then
    cp -f "${ROOT}/.env.example" "$BUILD_ENV_EXAMPLE"
else
    echo "WARNING: ${ROOT}/.env.example is missing; generating ${BUILD_ENV_EXAMPLE}" >&2
    cat > "$BUILD_ENV_EXAMPLE" <<'ENVEXAMPLE'
# IBM Content Manager connection alias from cmbicmsrvs.ini
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=CHANGE_ME

# Local IBM CM and Java installations
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0

# Optional DB2 settings used only by explicit --backfill operations.
# DB2_DATABASE=LSDB
# DB2_JDBC_URL=jdbc:db2:LSDB
# DB2_USER=icmadmin
# DB2_PASSWORD=CHANGE_ME
# DB2_SCHEMA=ICMADMIN
# DB2_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
ENVEXAMPLE
fi

# Build a transportable runtime bundle. It intentionally contains no IBM SDK
# or credentials; those are supplied by the target IBM CM installation.
RUNTIME_NAME="cm-retention-${APP_VERSION}"
RUNTIME_STAGE="${BUILD_DIR}/runtime/${RUNTIME_NAME}"
RUNTIME_TAR="${BUILD_DIR}/${RUNTIME_NAME}-runtime.tar.gz"

mkdir -p "${RUNTIME_STAGE}/bin" "${RUNTIME_STAGE}/build" "${RUNTIME_STAGE}/docs" \
    "${RUNTIME_STAGE}/profiles" "${RUNTIME_STAGE}/tests"
cp "${ROOT}/bin/cm-retention" "${RUNTIME_STAGE}/bin/cm-retention"
chmod 755 "${RUNTIME_STAGE}/bin/cm-retention"
cp "$CURRENT_JAR" "${RUNTIME_STAGE}/build/cm-retention.jar"
cp "$VERSIONED_JAR" "${RUNTIME_STAGE}/build/"
cp "${BUILD_DIR}/.version" "${RUNTIME_STAGE}/build/.version"
cp "${ROOT}/ret-policy.properties" "${RUNTIME_STAGE}/ret-policy.properties"
cp "${ROOT}/profiles/"*.properties "${RUNTIME_STAGE}/profiles/"
cp "$BUILD_ENV_EXAMPLE" "${RUNTIME_STAGE}/.env.example"
cp "${ROOT}/README.md" "${ROOT}/DOKUMENTATION.md" "${ROOT}/CHANGELOG.md" "${RUNTIME_STAGE}/"
cp "${ROOT}/docs/"*.md "${RUNTIME_STAGE}/docs/"
cp "${ROOT}/tests/selftest.sh" "${RUNTIME_STAGE}/tests/selftest.sh"
chmod 755 "${RUNTIME_STAGE}/tests/selftest.sh"

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
  4. Review ret-policy.properties and profiles/*.properties.
     Default AUTO_DELETE force-checkin is true.
  5. Verify the packaged version and pure self-test:
       cat build/.version
       bin/cm-retention version
       bin/cm-retention selftest
  6. Verify the target IBM CM environment:
       bin/cm-retention doctor
       bin/cm-retention status
  7. Start with read-only commands or --dry-run before a real write.

Create directly from a template, for example:

  bin/cm-retention create profiles/auto-delete-5y.properties --dry-run

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
printf 'Self-test:      %s\n' "passed"
printf 'Runtime JAR:    %s\n' "$CURRENT_JAR"
printf 'Versioned JAR:  %s\n' "$VERSIONED_JAR"
printf 'Version file:   %s\n' "${BUILD_DIR}/.version"
printf 'Policy config:  %s\n' "${BUILD_DIR}/ret-policy.properties"
printf 'Policy profiles:%s\n' " ${BUILD_DIR}/profiles/"
printf 'Env example:    %s\n' "$BUILD_ENV_EXAMPLE"
printf 'Runtime bundle: %s\n' "$RUNTIME_TAR"
if [[ -f "$CHECKSUM_FILE" ]]; then
    printf 'Checksums:      %s\n' "$CHECKSUM_FILE"
fi
