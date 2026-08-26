# CM Retention

`cm-retention` is a small administration CLI for **IBM Content Manager Enterprise Edition 8.7** retention and expiration policies.

Current version: **0.3.2**

The project intentionally stays narrow: Java 8, the IBM CM SDK already installed on the server, no GUI, no external CLI framework, and no additional runtime dependencies beyond the existing IBM CM / DB2 runtime.

## Main capabilities

- list and inspect retention policies
- list and inspect ItemTypes
- create fixed-time `AUTO_DELETE` policies
- create a complete policy directly from a properties template
- keep multiple reusable policy templates under `profiles/`
- assign and unassign policies
- process multiple ItemTypes with `--file`
- preview writes with `--dry-run`
- optionally backfill existing objects before assignment with `--backfill`
- load policy defaults from `ret-policy.properties`
- override policy defaults with `--properties FILE`
- show connection/runtime status
- run deeper diagnostics with `doctor`

The tool does **not** directly delete documents and does not invoke `deleteExpiredItems()` itself.

---

# CLI

```text
cm-retention
cm-retention status
cm-retention doctor

cm-retention policies
cm-retention policy [POLICY]
cm-retention itemtypes
cm-retention itemtype [ITEMTYPE]

cm-retention create [POLICY] [AGE]
cm-retention create --properties FILE
cm-retention assign [ITEMTYPE] [POLICY]
cm-retention unassign [ITEMTYPE]
cm-retention delete [POLICY]

cm-retention assign --file ITEMTYPES.txt POLICY
cm-retention unassign --file ITEMTYPES.txt

cm-retention assign ITEMTYPE POLICY --backfill
cm-retention assign --file ITEMTYPES.txt POLICY --backfill
```

Run without arguments in a terminal for the small interactive admin menu.

---

# Policy templates and defaults

Version 0.3.2 turns the properties file into a complete reusable policy template.

Every actual properties template must contain:

```properties
RET_POLICY_NAME=...
```

The default template is:

```text
ret-policy.properties
```

Default content:

```properties
RET_POLICY_NAME=AUTO_DELETE_1Y

retention.type=FIXED_TIME
retention.enabled=false

expiration.enabled=true
expiration.age=1y
expiration.action=AUTO_DELETE

auto-delete.schedule=0 2 * * *
auto-delete.commit-count=100
auto-delete.max-items=5000
auto-delete.max-duration=120
auto-delete.force-checkin=true
```

`auto-delete.force-checkin=true` corresponds to **"Einchecken vor Loeschen erzwingen"** and is intentionally enabled by default.

## Create directly from a template

No policy name or expiration argument is needed when the template contains them:

```bash
bin/cm-retention create \
  --properties profiles/auto-delete-5y.properties \
  --dry-run
```

Real creation:

```bash
bin/cm-retention create \
  --properties profiles/auto-delete-5y.properties
```

Automation:

```bash
bin/cm-retention create \
  --properties profiles/auto-delete-5y.properties \
  --yes
```

The supplied file **must contain `RET_POLICY_NAME`**. A selected properties file without that key is rejected instead of silently inheriting another policy name.

## Included templates

The repository and runtime bundle contain:

```text
profiles/auto-delete-1y.properties
profiles/auto-delete-5y.properties
profiles/auto-delete-10y.properties
```

You can copy one of these and create any number of environment-specific policy templates.

Example:

```properties
RET_POLICY_NAME=AUTO_DELETE_7Y_NIGHT
retention.type=FIXED_TIME
retention.enabled=false
expiration.enabled=true
expiration.age=7y
expiration.action=AUTO_DELETE
auto-delete.schedule=0 3 * * *
auto-delete.commit-count=100
auto-delete.max-items=5000
auto-delete.max-duration=120
auto-delete.force-checkin=true
```

Then:

```bash
bin/cm-retention create --properties profiles/auto-delete-7y-night.properties --dry-run
```

## Precedence

When creating a policy, values are resolved in this order:

```text
explicit CLI POLICY / AGE / options
        > --properties FILE
        > ret-policy.properties
        > built-in fallback
```

This gives two equally supported workflows.

Template-first:

```bash
bin/cm-retention create --properties profiles/auto-delete-5y.properties
```

CLI-first:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y
```

A positional policy name overrides `RET_POLICY_NAME` and a positional AGE overrides `expiration.age`:

```bash
bin/cm-retention create TEMP_POLICY 30d \
  --properties profiles/auto-delete-5y.properties \
  --dry-run
```

Explicit CLI options also override the selected properties file:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y \
  --properties profiles/auto-delete-5y.properties \
  --schedule "0 4 * * *" \
  --max-items 10000
```

The default force-checkin can be disabled for one create operation explicitly:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y --no-force-checkin
```

and explicitly enabled with:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y --force-checkin
```

Using both switches together is rejected.

## Supported semantic model

The properties file contains the semantic policy settings as well, but `cm-retention` intentionally keeps creation constrained to the currently supported safe model:

```text
retention.type     = FIXED_TIME
retention.enabled  = false
expiration.enabled = true
expiration.action  = AUTO_DELETE
```

Unsupported semantic values and unknown property names are rejected rather than silently creating a different policy type.

---

# Installation

There are two supported deployment models:

1. Build from source on a compatible IBM CM host.
2. Build once and copy the precompiled runtime bundle to servers without Git or `javac`.

The second model is recommended for controlled TEST/PROD servers.

## Runtime prerequisites

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

Required only when compiling:

```text
${JAVA_HOME}/bin/javac
${JAVA_HOME}/bin/jar
```

Recommended runtime user:

```text
ibmcmadm
```

## Configuration

Create the local configuration:

```bash
cp .env.example .env
chmod 600 .env
vi .env
```

Example:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=CHANGE_ME
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

The launcher refuses insecure `.env` permissions.

For TEST and PROD, prefer separate files:

```text
.env.test
.env.prod
```

and call them explicitly:

```bash
bin/cm-retention --env .env.test status
bin/cm-retention --env .env.prod status
```

---

# Build

Run on a compatible IBM CM 8.7 build host:

```bash
./build.sh
```

The version is read from `src/CmRetention.java` and written into the JAR manifest and `build/.version`.

For version 0.3.2 the build creates:

```text
build/cm-retention.jar
build/cm-retention-0.3.2.jar
build/.version
build/ret-policy.properties
build/profiles/auto-delete-1y.properties
build/profiles/auto-delete-5y.properties
build/profiles/auto-delete-10y.properties
build/cm-retention-0.3.2-runtime.tar.gz
build/SHA256SUMS-0.3.2
```

Meaning:

- `cm-retention.jar` - stable runtime filename used by the launcher
- `cm-retention-0.3.2.jar` - immutable versioned JAR
- `.version` - exact compiled version
- `ret-policy.properties` - default policy template
- `profiles/` - reusable policy templates
- `*-runtime.tar.gz` - transportable runtime package
- `SHA256SUMS-*` - integrity checks when `sha256sum` is available

Verify:

```bash
cat build/.version
ls -lh build/cm-retention*.jar
find build/profiles -maxdepth 1 -type f -name '*.properties' -print
bin/cm-retention version
```

Expected:

```text
0.3.2
cm-retention 0.3.2
```

## Deployment without Git

On the build host:

```bash
./build.sh
```

Copy:

```text
build/cm-retention-0.3.2-runtime.tar.gz
```

to the target server.

On the target:

```bash
cd /home/ibmcmadm
tar -xzf cm-retention-0.3.2-runtime.tar.gz
cd cm-retention-0.3.2

cp .env.example .env
chmod 600 .env
vi .env

cat build/.version
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
```

The target server does not need Git or `javac`.

The runtime archive includes both `ret-policy.properties` and `profiles/*.properties`.

---

# Creating a policy

The shortest template workflow is:

```bash
bin/cm-retention create --properties profiles/auto-delete-1y.properties --dry-run
```

If the plan is correct:

```bash
bin/cm-retention create --properties profiles/auto-delete-1y.properties
```

The default template can even be used with:

```bash
bin/cm-retention create --dry-run
```

because `ret-policy.properties` contains both `RET_POLICY_NAME` and `expiration.age`.

The classic syntax remains available:

```bash
bin/cm-retention create AUTO_DELETE_10Y 10y
```

Advanced overrides:

```bash
bin/cm-retention create AUTO_DELETE_10Y 10y \
  --schedule "0 4 * * *" \
  --commit-count 200 \
  --max-items 10000 \
  --max-duration 180
```

The plan prints the effective properties source and the final `force-checkin` state before creation.

---

# Assigning a policy

Normal assignment does not touch existing document expiration metadata:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y
```

Automation:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --yes
```

Dry-run:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --dry-run
```

---

# Existing-item backfill

IBM CM does not automatically populate expiration metadata on existing root rows merely because a policy is assigned later.

Use `--backfill` only when existing rows should also receive the expiration date before assignment.

Always start with:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

The backfill resolves the ItemType root component/table automatically. The user never supplies an `ICMUT...` table name.

## Correct SQL semantics

For eligible rows the equivalent DB2 operation is:

```sql
UPDATE ICMADMIN.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

Important: the physical creation timestamp column is:

```text
CREATETS
```

not `ICM$CREATETS`.

For a one-year policy the plan therefore prints:

```text
Formula : ICM$AUTODELETEDATE = CREATETS + 1 YEAR
```

The expiration duration is read from the selected policy and is never hard-coded to one year.

## Backfill safety rules

- only rows where both retention and auto-delete dates are NULL are changed
- existing dates are never overwritten
- NULL `CREATETS` causes refusal before update
- a different currently assigned policy causes refusal
- only supported fixed-time, retention-disabled, AUTO_DELETE policies are accepted
- root table names are generated internally and validated
- DB2 UPDATE is committed and verified before policy assignment begins
- if the DB2 commit succeeded but later assignment/verification is not clean, exit code `6` is returned
- multi-segment cases currently fail closed instead of partially updating a segment

Real execution:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

Automation:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --yes
```

See [docs/BACKFILL.md](docs/BACKFILL.md) for the detailed operational procedure.

---

# Batch mode with `--file`

Example:

```text
# itemtypes.txt
AM
INVOICE
CONTRACT
```

Assign:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y
```

Backfill + assign:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y \
  --backfill --dry-run
```

Real batch:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y \
  --backfill --yes
```

Before the first mutation, all ItemTypes are validated. Actual execution is sequential and deliberately non-atomic. If one ItemType fails in phase 2, processing stops and earlier successful changes remain committed.

---

# DB2 configuration for `--backfill`

Normal non-backfill operations do not need the additional DB2 settings.

Optional `.env` values:

```dotenv
DB2_DATABASE=LSDB
DB2_JDBC_URL=jdbc:db2:LSDB
DB2_USER=icmadmin
DB2_PASSWORD=CHANGE_ME
DB2_SCHEMA=ICMADMIN
DB2_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

A type-4 URL can also be used:

```dotenv
DB2_JDBC_URL=jdbc:db2://dbhost.example:50000/LSDB
```

If DB2 database/user/password are omitted, the corresponding CM values are used as defaults.

---

# Safety model

Every normal IBM-CM write follows:

```text
resolve
 -> read current state
 -> validate
 -> print plan
 -> dry-run / confirmation
 -> mutate
 -> commit
 -> reconnect / verify persisted state
```

Backfill adds a guarded DB2 phase before assignment:

```text
plan/count
 -> confirm
 -> DB2 UPDATE
 -> DB2 COMMIT
 -> DB2 verification
 -> policy assignment
 -> reconnect/verification
 -> final DB2 + policy verification
```

A DB2 backfill and IBM-CM API assignment are not one distributed transaction. Partial-success conditions are therefore surfaced explicitly instead of hidden.

---

# Exit codes

| Code | Meaning |
|---:|---|
| `0` | success / no change / successful dry-run |
| `2` | CLI, configuration, properties or preflight error |
| `3` | IBM CM / DB2 runtime operation error |
| `4` | requested ItemType or policy not found |
| `5` | unsafe/conflicting operation refused |
| `6` | verification warning/failure or partial-success condition |

Scripts should always inspect the return code, especially `6`.

---

# Documentation

- [Detailed operations guide](DOKUMENTATION.md)
- [Backfill workflow](docs/BACKFILL.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Metadata repair notes](docs/METADATA_REPAIR.md)
- [Changelog](CHANGELOG.md)
