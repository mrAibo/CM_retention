# CM Retention

`cm-retention` is a small administration CLI for **IBM Content Manager Enterprise Edition 8.7** retention and expiration policies.

Current version: **0.3.0**

The project intentionally stays narrow: Java 8, the IBM CM SDK already installed on the server, one launcher, no GUI, no external CLI framework and no additional runtime dependencies beyond the existing IBM CM/DB2 runtime.

## What it does

- list and inspect retention policies
- list and inspect ItemTypes
- create fixed-time `AUTO_DELETE` policies
- assign and unassign policies
- process multiple ItemTypes from a file with `--file`
- preview all writes with `--dry-run`
- optionally backfill existing objects before policy assignment with `--backfill`
- show environment/connection status
- run local + IBM CM diagnostics with `doctor`

It does **not** directly delete documents, invoke `deleteExpiredItems()`, accept arbitrary SQL/table names, or modify IBM CM system tables except for the explicitly requested and guarded existing-item backfill described below.

---

# CLI

```text
cm-retention
cm-retention status
cm-retention policies
cm-retention policy [POLICY]
cm-retention itemtypes
cm-retention itemtype [ITEMTYPE]
cm-retention create [POLICY] [AGE]
cm-retention assign [ITEMTYPE] [POLICY]
cm-retention assign ITEMTYPE POLICY --backfill
cm-retention assign --file ITEMTYPES.txt POLICY
cm-retention assign --file ITEMTYPES.txt POLICY --backfill
cm-retention unassign [ITEMTYPE]
cm-retention unassign --file ITEMTYPES.txt
cm-retention delete [POLICY]
cm-retention doctor
```

Run without arguments for the interactive admin mode.

Normal interactive writes show a plan and require an explicit `y` at a conservative `[y/N]` prompt. Non-interactive writes require `--yes`. `--dry-run` never changes IBM CM.

---

# Installation

Two deployment models are supported:

1. **Build from source on a compatible IBM CM 8.7 host.**
2. **Build once, then deploy the precompiled runtime archive to TEST/PROD servers without Git or `javac`.**

The second model is recommended for controlled target servers.

## 1. Runtime prerequisites

Typical environment:

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

The configured IBM CM library-server alias must already exist in the local IBM CM configuration.

## 2A. Build from source

With Git:

```bash
cd /home/ibmcmadm
git clone https://github.com/mrAibo/CM_retention.git
cd CM_retention
```

Without Git, download the repository ZIP on another workstation, transfer it to the CM host and extract it there. `build.sh` itself does not require Git.

Create the configuration:

```bash
cp .env.example .env
chmod 600 .env
vi .env
```

Minimal example:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=CHANGE_ME
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Build:

```bash
./build.sh
```

For version 0.3.0 the build creates:

```text
build/cm-retention.jar
build/cm-retention-0.3.0.jar
build/.version
build/cm-retention-0.3.0-runtime.tar.gz
build/SHA256SUMS-0.3.0
```

Check:

```bash
cat build/.version
bin/cm-retention version
```

Expected:

```text
0.3.0
cm-retention 0.3.0
```

Then run read-only checks:

```bash
bin/cm-retention doctor
bin/cm-retention status
bin/cm-retention policies
bin/cm-retention itemtypes
```

## 2B. Deploy without Git

Build once on a compatible IBM CM 8.7 host:

```bash
./build.sh
```

Transfer:

```text
build/cm-retention-0.3.0-runtime.tar.gz
```

to the target server using your approved internal transfer method.

On the target:

```bash
cd /home/ibmcmadm
tar -xzf cm-retention-0.3.0-runtime.tar.gz
cd cm-retention-0.3.0

cp .env.example .env
chmod 600 .env
vi .env

cat build/.version
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
```

The target does **not** need Git or a Java compiler. It still needs a compatible IBM CM 8.7 runtime and Java 8.

For best compatibility, build the runtime archive against the same IBM CM level/fix pack used by the target environment.

## 3. Separate TEST and PROD

Use dedicated configuration files rather than editing one `.env` back and forth:

```bash
cp .env.example .env.test
cp .env.example .env.prod
chmod 600 .env.test .env.prod
```

Then address the target explicitly:

```bash
bin/cm-retention --env .env.test status
bin/cm-retention --env .env.prod status
```

Before a production write, always run `status` with the exact same `--env` file first.

---

# Configuration

Normal policy administration uses only the CM settings:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=CHANGE_ME
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

`.env` must not be committed and must have mode `0600` or stricter.

## Additional DB2 settings for `--backfill`

Only explicit backfill operations need direct DB2 access. Optional settings:

```dotenv
DB2_DATABASE=LSDB
DB2_JDBC_URL=jdbc:db2:LSDB
DB2_USER=icmadmin
DB2_PASSWORD=CHANGE_ME
DB2_SCHEMA=ICMADMIN
DB2_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

Defaults:

```text
DB2_DATABASE  -> CM_DATABASE
DB2_JDBC_URL  -> jdbc:db2:<DB2_DATABASE>
DB2_USER      -> CM_USER
DB2_PASSWORD  -> CM_PASSWORD
DB2_SCHEMA    -> ICMADMIN
```

If the CM alias is not also a usable DB2 alias, configure `DB2_JDBC_URL` explicitly. Example type-4 URL:

```dotenv
DB2_JDBC_URL=jdbc:db2://dbhost.example:50000/LSDB
```

The DB2 user needs SELECT permission on the relevant CM metadata/root tables and UPDATE permission on the target root table.

`DB2_JDBC_JAR` is only necessary when the DB2 JCC driver is not already available through the IBM CM classpath or one of the standard DB2 V11.5 locations checked by the launcher.

No DB2 or CM password is accepted as a command-line option.

---

# Common operations

List policies:

```bash
bin/cm-retention policies
```

Inspect a policy:

```bash
bin/cm-retention policy AUTO_DELETE_1Y
```

List ItemTypes:

```bash
bin/cm-retention itemtypes
```

Inspect one ItemType:

```bash
bin/cm-retention itemtype AM
```

Create a one-year AUTO_DELETE policy:

```bash
bin/cm-retention create AUTO_DELETE_1Y 1y
```

Preview assignment:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --dry-run
```

Assign interactively:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y
```

Automation:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --yes
```

Unassign:

```bash
bin/cm-retention unassign AM
```

Delete an unused policy:

```bash
bin/cm-retention delete AUTO_DELETE_1Y
```

---

# Existing-item backfill

A later policy assignment does not by itself populate expiration metadata for already existing objects. `--backfill` is an explicit opt-in workflow for the case where existing root rows still have both dates NULL.

Always start with:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

A dry-run resolves the ItemType, policy and root component/table and reports:

```text
Root rows total
Missing both dates
Backfillable rows
NULL create timestamp
Immediately expired after
Already auto-delete dated
Retention date already set
```

It also prints the exact calculated formula, for example:

```text
ICM$AUTODELETEDATE = ICM$CREATETS + 1 YEAR
```

The duration is read from the selected policy. It is not hard-coded to one year.

For eligible rows the effective SQL is equivalent to:

```sql
UPDATE ICMADMIN.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = ICM$CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND ICM$CREATETS IS NOT NULL;
```

The root table is resolved automatically from IBM CM metadata. The CLI never accepts an arbitrary table name.

## Backfill safety conditions

`--backfill` accepts only:

```text
Retention type     FIXED_TIME
Retention enabled  false
Expiration enabled true
Expiration action  AUTO_DELETE
Expiration period  > 0
```

The command refuses:

- event-driven policies
- retention-enabled policies
- non-AUTO_DELETE policies
- unsupported expiration units
- rows with NULL `ICM$CREATETS`
- an ItemType that already uses a different policy

Existing non-NULL retention/auto-delete dates are never overwritten.

## Real backfill + assignment

Interactive:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

Automation:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --yes
```

Order:

```text
plan/count
-> confirmation
-> DB2 UPDATE
-> DB2 COMMIT
-> verify no eligible NULL rows remain
-> IBM CM policy assignment
-> reconnect/persisted-state verification
-> final DB2 + policy verification
```

The DB2 update and IBM CM API assignment are **not one distributed transaction**. If DB2 has already committed but assignment/final verification is not a clean success, the command returns exit code `6` and tells the operator to review/retry. The backfill itself is idempotent for rows already updated.

Rows whose calculated auto-delete date is already in the past become immediately eligible for AUTO_DELETE after the policy is assigned. Review the dry-run count before production execution.

Full backfill documentation: [docs/BACKFILL.md](docs/BACKFILL.md)

---

# Multiple ItemTypes with `--file`

File format:

```text
# itemtypes.txt
AM
INVOICE
CONTRACT
```

Blank lines and `#` comments are ignored. Duplicate ItemType names are rejected. Names must be exact.

Assign without backfill:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y
```

Assign with existing-item backfill:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill
```

Non-interactive:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --yes
```

Before the first mutation, every ItemType in the file is validated. If one fails validation, phase 1 stops and no batch changes are made.

Actual phase-2 execution is sequential and deliberately non-atomic. With backfill each ItemType is processed as:

```text
DB2 backfill -> verify -> policy assignment -> final verify
```

If one ItemType fails, processing stops. Earlier successful ItemTypes remain committed.

Unassign from a file:

```bash
bin/cm-retention unassign --file itemtypes.txt --dry-run
bin/cm-retention unassign --file itemtypes.txt
```

`--backfill` is not supported for `unassign`.

---

# Policy defaults

The normal create command is intentionally short:

```bash
bin/cm-retention create RET_10Y 10y
```

Defaults:

```text
schedule       daily 02:00 (0 2 * * *)
commit count   100
max items      5000
max duration   120 minutes
force check-in false
```

Advanced overrides remain available:

```bash
bin/cm-retention create RET_10Y 10y \
  --schedule "0 4 * * *" \
  --commit-count 200 \
  --max-items 10000 \
  --max-duration 180
```

---

# Safety model

Normal write operations follow:

```text
resolve -> read current state -> validate -> print plan
        -> dry-run/confirm -> mutate -> commit -> verify persisted state
```

Important behavior:

- assigning the already active policy is a successful no-op
- unassigning an ItemType without a policy is a successful no-op
- assigned policies cannot be deleted
- unknown/duplicate options are rejected
- scripts require exact identifiers
- interactive selection may use exact/unambiguous prefixes
- `--yes` is required for non-TTY writes
- `--dry-run` performs validation without mutation
- problematic IBM CM updates are reconnected/re-read before reporting the final state
- exit code `6` distinguishes partial/persisted-warning states from clean success

---

# Exit codes

| Code | Meaning |
|---:|---|
| `0` | success, no change or successful dry-run |
| `2` | CLI/configuration/preflight/confirmation error |
| `3` | IBM CM, DB2 or runtime operation error |
| `4` | requested ItemType/policy not found |
| `5` | unsafe/conflicting operation refused |
| `6` | verification/partial-success warning; state may already be partly or fully persisted |

Scripts should always inspect the return code, especially `6`.

---

# Updating

Source installation:

```bash
cd /home/ibmcmadm/CM_retention
git pull --ff-only
rm -rf build
./build.sh
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
```

A `git pull` does not rebuild the JAR. The launcher refuses stale source/build combinations.

No-Git target servers should receive a newly generated versioned runtime archive instead of individual source files.

---

# Documentation

- [Detailed operations guide](DOKUMENTATION.md)
- [Existing-item backfill](docs/BACKFILL.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [IBM CM metadata repair notes](docs/METADATA_REPAIR.md)
- [Changelog](CHANGELOG.md)
