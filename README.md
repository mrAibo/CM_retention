# CM Retention

`cm-retention` is a small administration CLI for **IBM Content Manager Enterprise Edition 8.7** retention and expiration policies.

Current version: **0.2.1**

The project intentionally stays narrow: Java 8, the IBM CM SDK already installed on the server, one launcher, no GUI, no external CLI framework and no additional runtime dependencies.

## What it does

- list retention policies and their assignments
- inspect a policy in detail
- list and inspect item types
- create fixed-time `AUTO_DELETE` expiration policies
- assign and unassign a policy to an item type
- safely process multiple item types from a file with `--file`
- delete an unused policy
- show environment/connection status
- run local + IBM CM diagnostics with `doctor`
- preview write operations with `--dry-run`

It does **not** delete documents directly, run `deleteExpiredItems()`, backfill existing items, or modify IBM CM system tables.

## CLI

```text
cm-retention
cm-retention status
cm-retention policies
cm-retention policy [POLICY]
cm-retention itemtypes
cm-retention itemtype [ITEMTYPE]
cm-retention create [POLICY] [AGE]
cm-retention assign [ITEMTYPE] [POLICY]
cm-retention assign --file ITEMTYPES.txt POLICY
cm-retention unassign [ITEMTYPE]
cm-retention unassign --file ITEMTYPES.txt
cm-retention delete [POLICY]
cm-retention doctor
```

Run without arguments for the interactive admin mode.

---

# Installation

There are two supported deployment models:

1. **Build from source on an IBM CM host**.
2. **Deploy a precompiled runtime bundle** to a target server that has no Git and does not need `javac`.

The second model is recommended when TEST/PROD servers are tightly controlled.

## 1. Runtime prerequisites

The target server must already have a compatible IBM Content Manager 8.7 installation and Java 8.

Typical paths:

```text
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Required at runtime:

```text
${IBMCMROOT}/lib/cmbicmsdk81.jar
${IBMCMROOT}/lib/
${IBMCMROOT}/cmgmt/
${JAVA_HOME}/bin/java
```

Required only when building from source:

```text
${JAVA_HOME}/bin/javac
${JAVA_HOME}/bin/jar
```

Recommended runtime user:

```text
ibmcmadm
```

The configured CM library-server alias, for example `LSDB`, must already exist in the local IBM CM configuration.

---

# A. Build from source

## A1. Obtain the source

With Git:

```bash
cd /home/ibmcmadm
git clone https://github.com/mrAibo/CM_retention.git
cd CM_retention
```

If Git is not installed, download the repository ZIP on another workstation, transfer it to the CM host and extract it there. Git is not required by `build.sh`.

## A2. Create configuration

```bash
cp .env.example .env
chmod 600 .env
vi .env
```

Example:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=change-me
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

The launcher refuses configuration files that are readable by group or other users.

## A3. Build

```bash
./build.sh
```

The version is read directly from `src/CmRetention.java`, compiled into the JAR manifest and written into the build directory.

For version `0.2.1` the build produces:

```text
build/cm-retention.jar
build/cm-retention-0.2.1.jar
build/.version
build/cm-retention-0.2.1-runtime.tar.gz
build/SHA256SUMS-0.2.1
```

Meaning:

- `cm-retention.jar` — stable runtime filename used by the launcher
- `cm-retention-0.2.1.jar` — immutable versioned JAR
- `.version` — exact compiled version
- `*-runtime.tar.gz` — transportable no-Git runtime package
- `SHA256SUMS-*` — checksums for the versioned JAR and runtime archive when `sha256sum` is available

Verify:

```bash
cat build/.version
ls -lh build/cm-retention*.jar build/*runtime.tar.gz
```

Expected:

```text
0.2.1
```

## A4. Test the freshly built version

```bash
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
bin/cm-retention policies
bin/cm-retention itemtypes
```

Only after the read-only checks succeed should a write or batch operation be tested.

---

# B. Precompiled deployment without Git

This is the recommended workflow when the target CM server has **no Git**.

## B1. Build once on a compatible IBM CM 8.7 host

On a build/test CM host containing the real IBM SDK:

```bash
./build.sh
```

Use the resulting archive:

```text
build/cm-retention-0.2.1-runtime.tar.gz
```

The runtime archive intentionally does **not** contain IBM proprietary SDK/runtime libraries and does not contain credentials. It contains only the compiled application, launcher, example configuration and documentation.

For best compatibility, build against the same IBM CM 8.7 level/fix pack used by the target servers.

## B2. Copy the runtime package to the target

For example:

```bash
scp build/cm-retention-0.2.1-runtime.tar.gz \
    ibmcmadm@TARGET:/home/ibmcmadm/
```

Any approved internal file-transfer method can be used instead of `scp`.

## B3. Extract on the target

```bash
cd /home/ibmcmadm
tar -xzf cm-retention-0.2.1-runtime.tar.gz
cd cm-retention-0.2.1
```

No Git checkout is needed and no Java compiler is needed on the target.

## B4. Configure

```bash
cp .env.example .env
chmod 600 .env
vi .env
```

Then verify:

```bash
cat build/.version
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
```

Expected:

```text
cm-retention 0.2.1
```

## B5. Verify checksums before transfer/deployment

On the build host:

```bash
cd build
sha256sum -c SHA256SUMS-0.2.1
```

This verifies the immutable JAR and runtime archive.

---

# Separate TEST and PROD configurations

TEST and PROD should be treated as dedicated server configurations.

Do not edit one shared `.env` back and forth.

Example:

```bash
cp .env.example .env.test
cp .env.example .env.prod
chmod 600 .env.test .env.prod
```

Use them explicitly:

```bash
bin/cm-retention --env .env.test status
bin/cm-retention --env .env.prod status
```

Always run `status` against PROD before a write.

---

# Safe batch mode with `--file`

`--file` is supported for **assign** and **unassign** only.

It is intended for controlled administration of multiple item types while preserving the single-item verification logic.

## File format

One exact IBM CM ItemType name per line:

```text
# retention migration wave 1
INVOICE
CONTRACT
CUSTOMER_DOC

# blank lines are ignored
MAIL_ARCHIVE
```

Rules:

- one ItemType per line
- leading/trailing whitespace is removed
- empty lines are ignored
- lines beginning with `#` are comments
- ItemType names must be exact; no prefix matching is used in files
- duplicate ItemTypes are rejected before any change

## Assign one policy to all ItemTypes in a file

Always start with dry-run:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_5Y --dry-run
```

Interactive execution:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_5Y
```

The tool validates **every** ItemType first. Only when all validations succeed does it offer the batch confirmation:

```text
Apply batch to 4 item types? [y/N]:
```

Non-interactive execution:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_5Y --yes
```

## Unassign all ItemTypes in a file

Dry-run:

```bash
bin/cm-retention unassign --file itemtypes.txt --dry-run
```

Interactive:

```bash
bin/cm-retention unassign --file itemtypes.txt
```

Automation:

```bash
bin/cm-retention unassign --file itemtypes.txt --yes
```

## Batch safety model

The batch has two phases:

```text
Phase 1: validate every ItemType with the normal Java dry-run
Phase 2: execute each validated ItemType sequentially
```

If Phase 1 fails, **no batch change is started**.

Batch execution is deliberately **not atomic**. IBM CM changes are committed and verified item by item. If an error occurs during Phase 2:

- processing stops immediately
- no later ItemTypes are touched
- earlier successful ItemTypes remain committed
- the command returns the failing item's exit code
- the administrator must review current state before retrying

This design keeps the existing reconnect/persisted-state verification and exit-code `6` semantics for every individual ItemType.

There is intentionally no `--continue-on-error` option.

---

# Normal examples

List policies:

```bash
bin/cm-retention policies
```

Inspect a policy:

```bash
bin/cm-retention policy AUTO_DELETE_5Y
```

Create a policy:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y
```

Assign one ItemType:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y
```

Preview first:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y --dry-run
```

For non-interactive automation:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y --yes
```

---

# Interactive vs automation

Interactive terminal:

- missing single-item arguments can be selected interactively
- exact/unambiguous prefix matching is available in the interactive selector
- every write defaults to **No** (`[y/N]`)

Non-TTY / cron / pipeline:

- required identifiers must be explicit
- exact names only
- real writes require `--yes`
- `--dry-run` never requires `--yes`

File batches follow the same rule: interactive confirmation or `--yes`.

---

# Safety model

Every single write follows:

```text
resolve -> read current state -> validate -> print plan
        -> dry-run / confirm -> mutate -> commit -> verify persisted state
```

Important boundaries:

- assign/unassign are idempotent
- an assigned policy cannot be deleted
- existing documents are not retroactively backfilled
- passwords are never accepted as a CLI password argument
- unknown options are rejected
- stale plans are refused
- after problematic IBM CM ItemType updates, the tool reconnects and reads the real persisted state
- exit code `6` means the requested state may already be persisted although IBM CM reported a secondary error

---

# Status and doctor

```bash
bin/cm-retention status
bin/cm-retention doctor
```

The launcher also validates that source and compiled version match on a source installation. A stale JAR is refused instead of silently starting an old CLI.

A precompiled runtime package uses `build/.version` as its deployment version and does not require source files.

---

# Updating

## Source installation with Git

```bash
git pull --ff-only
./build.sh
bin/cm-retention version
bin/cm-retention status
```

`git pull` does not rebuild Java automatically.

## Server without Git

Build a new runtime archive on the build host and replace the extracted runtime directory on the target with the new versioned directory.

Do not overwrite an old directory blindly. Keeping versioned directories makes rollback simple:

```text
/home/ibmcmadm/cm-retention-0.2.0
/home/ibmcmadm/cm-retention-0.2.1
```

Copy the existing `.env` only after verifying ownership and mode, then run `doctor` and `status` before use.

---

# Exit codes

| Code | Meaning |
|---:|---|
| `0` | success / no change / successful dry-run |
| `2` | CLI, configuration, preflight, file-format or confirmation error |
| `3` | IBM CM / runtime operation error |
| `4` | requested ItemType or policy not found |
| `5` | unsafe/conflicting operation or stale state |
| `6` | verification warning/failure; requested state may already be persisted despite a secondary IBM CM error |

Batch mode stops on the first non-zero item result and returns that result.

---

# Advanced create options

```bash
bin/cm-retention create RET_10Y 10y \
  --schedule "0 4 * * *" \
  --commit-count 200 \
  --max-items 10000 \
  --max-duration 180
```

Show complete help:

```bash
bin/cm-retention create --help
```

---

# Compatibility with 0.1.x

Legacy command forms remain accepted with a deprecation warning. New scripts should use the top-level v0.2 commands.

---

# Documentation

- [Detailed operations guide](DOKUMENTATION.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [IBM CM metadata repair notes](docs/METADATA_REPAIR.md)
- [Changelog](CHANGELOG.md)
