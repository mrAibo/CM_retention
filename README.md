# CM Retention

`cm-retention` is a small administration CLI for **IBM Content Manager Enterprise Edition 8.7** retention and expiration policies.

Current version: **0.3.4**

The project intentionally stays narrow: Java 8, the IBM CM SDK already installed on the server, no GUI, no external CLI framework, and no additional runtime dependencies beyond the existing IBM CM / DB2 runtime.

## Main capabilities

- list and inspect retention policies
- list and inspect ItemTypes
- create fixed-time `AUTO_DELETE` policies
- create policies directly from reusable `.properties` templates
- automatically recognize a readable `.properties` file after `create`
- keep multiple policy templates under `profiles/`
- assign and unassign policies
- process multiple ItemTypes with `--file` in one native Java batch runtime
- preview writes with `--dry-run`
- optionally backfill existing objects before assignment with `--backfill`
- load policy defaults from `ret-policy.properties`
- show connection/runtime status and run diagnostics with `doctor`

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

cm-retention create
cm-retention create FILE.properties
cm-retention create POLICY AGE
cm-retention create --properties FILE

cm-retention assign ITEMTYPE POLICY
cm-retention unassign ITEMTYPE
cm-retention delete POLICY

cm-retention assign --file ITEMTYPES.txt POLICY
cm-retention unassign --file ITEMTYPES.txt

cm-retention assign ITEMTYPE POLICY --backfill
cm-retention assign --file ITEMTYPES.txt POLICY --backfill
```

Run without arguments in a terminal for the small interactive admin menu.

## Inspecting policy usage

A compact policy list is available with:

```bash
bin/cm-retention policies
```

The `ITEMTYPES` column shows how many ItemTypes currently use each policy.

For a single policy, use:

```bash
bin/cm-retention policy POLICY
```

Example:

```bash
bin/cm-retention policy AUTO_DELETE_5Y
```

In addition to the policy parameters, the command prints the exact ItemTypes currently assigned to that policy:

```text
Assigned itemtypes:         3
  - AM
  - CONTRACT
  - INVOICE
```

If the policy is unused, the output is:

```text
Assigned itemtypes:         0
```

The ItemType names are sorted alphabetically. This is useful before changing or deleting a policy.

---

# Policy templates

Every actual policy template contains at least:

```properties
RET_POLICY_NAME=...
expiration.age=...
```

The complete supported template format is:

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

`auto-delete.max-duration` is expressed in **seconds**. For example, `120` means 120 seconds (2 minutes), not 120 minutes.

`auto-delete.force-checkin=true` corresponds to **"Einchecken vor Löschen erzwingen"** and is intentionally enabled by default.

The repository and runtime bundle contain:

```text
ret-policy.properties
profiles/auto-delete-1y.properties
profiles/auto-delete-5y.properties
profiles/auto-delete-10y.properties
```

## Recommended create syntax

The normal template workflow is now simply:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --dry-run
```

If the plan is correct:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties
```

For automation:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --yes
```

The file can also follow normal flags:

```bash
bin/cm-retention create --dry-run profiles/auto-delete-5y.properties
```

The tool recognizes the argument as a template only when it:

- ends with `.properties`
- exists
- is a regular file
- is readable

The detected shorthand is internally normalized to the explicit form:

```bash
bin/cm-retention create --properties profiles/auto-delete-5y.properties
```

The explicit form remains fully supported.

## Ambiguity rule

Automatic template mode accepts the template as the **only positional create argument**.

This is valid:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --schedule "0 4 * * *"
```

This is deliberately rejected:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties 10y
```

If you want to use a template but override the policy name or expiration age positionally, use the explicit form:

```bash
bin/cm-retention create TEMP_POLICY 10y \
  --properties profiles/auto-delete-5y.properties \
  --dry-run
```

This avoids ambiguous interpretation.

## Default template

Because the default `ret-policy.properties` contains both `RET_POLICY_NAME` and `expiration.age`, this also works:

```bash
bin/cm-retention create --dry-run
```

and then:

```bash
bin/cm-retention create
```

## Precedence

When creating a policy, values are resolved in this order:

```text
explicit CLI POLICY / AGE / options
        > selected properties template
        > default ret-policy.properties
        > built-in fallback
```

Classic CLI creation remains supported:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y
```

Advanced overrides remain available:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties \
  --schedule "0 4 * * *" \
  --commit-count 200 \
  --max-items 10000 \
  --max-duration 180
```

`--max-duration` is also in seconds, so `--max-duration 180` means 3 minutes.

Force-checkin can be changed for one create operation:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --no-force-checkin
```

`--force-checkin` and `--no-force-checkin` together are rejected.

## Supported semantic model

Policy creation remains intentionally constrained to:

```text
retention.type     = FIXED_TIME
retention.enabled  = false
expiration.enabled = true
expiration.action  = AUTO_DELETE
```

Unsupported semantic values and unknown property names are rejected.

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

and invoke them explicitly:

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

For version 0.3.4 the build creates:

```text
build/cm-retention.jar
build/cm-retention-0.3.4.jar
build/.version
build/ret-policy.properties
build/profiles/auto-delete-1y.properties
build/profiles/auto-delete-5y.properties
build/profiles/auto-delete-10y.properties
build/cm-retention-0.3.4-runtime.tar.gz
build/SHA256SUMS-0.3.4
```

Verify:

```bash
cat build/.version
ls -lh build/cm-retention*.jar
find build/profiles -maxdepth 1 -type f -name '*.properties' -print
bin/cm-retention version
```

Expected:

```text
0.3.4
cm-retention 0.3.4
```

## Deployment without Git

On the build host:

```bash
./build.sh
```

Copy:

```text
build/cm-retention-0.3.4-runtime.tar.gz
```

to the target server.

On the target:

```bash
cd /home/ibmcmadm
tar -xzf cm-retention-0.3.4-runtime.tar.gz
cd cm-retention-0.3.4

cp .env.example .env
chmod 600 .env
vi .env

cat build/.version
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
```

The target server does not need Git or `javac`.

---

# Assigning a policy

Normal assignment does not touch existing document expiration metadata:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --dry-run
bin/cm-retention assign AM AUTO_DELETE_1Y
```

Automation:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --yes
```

---

# Existing-item backfill

Use `--backfill` only when existing rows should also receive the expiration date before assignment.

Always start with:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

The backfill resolves the ItemType root component/table automatically. The user never supplies an `ICMUT...` table name.

For eligible rows the equivalent DB2 operation is:

```sql
UPDATE ICMADMIN.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

Important: the physical creation timestamp column is `CREATETS`, not `ICM$CREATETS`.

For a one-year policy the plan therefore prints:

```text
Formula : ICM$AUTODELETEDATE = CREATETS + 1 YEAR
```

Safety rules include:

- existing retention/auto-delete dates are never overwritten
- NULL `CREATETS` causes refusal before update
- a different currently assigned policy causes refusal
- only supported FIXED_TIME/AUTO_DELETE policies are accepted
- root table names are generated internally and validated
- DB2 UPDATE is committed and verified before policy assignment
- a committed backfill followed by an unclean assignment/verification returns exit code `6`
- multi-segment cases fail closed

Real execution:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

See [docs/BACKFILL.md](docs/BACKFILL.md) for the detailed procedure.

---

# Batch mode with `--file`

Example:

```text
# itemtypes.txt
AM
INVOICE
CONTRACT
```

Assign dry-run:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --dry-run
```

Backfill + assign dry-run:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
```

Real batch:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --yes
```

Since 0.3.4, the complete `--file` workflow runs inside **one JVM** instead of starting a new JVM for every ItemType.

Phase 1:

- opens/reuses one CM runtime for the complete validation pass
- resolves the target policy once
- validates every ItemType before the first write
- with `--backfill`, reuses one DB2 JDBC connection for the batch
- with `--backfill`, calculates all seven plan counters with one aggregate SELECT per root table instead of seven independent COUNT queries

Before Phase 2, the CM validation session is deliberately discarded so writes do not rely on potentially stale metadata. Phase 2 remains **sequential and fail-fast**. After every CM write, the existing reconnect-based persisted-state verification remains enabled.

No parallel UPDATE/write execution is performed in 0.3.4. This is intentional: multiple simultaneous large DB2 updates could increase transaction-log, I/O and lock pressure. If one ItemType fails in Phase 2, processing stops and earlier successful changes remain committed.

The output identifies the optimized runtime explicitly:

```text
Batch mode (native Java runtime)
  Runtime   : single JVM
```

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

Batch mode adds:

```text
one JVM
 -> validate ALL ItemTypes
 -> one confirmation
 -> discard validation CM session
 -> sequential per-ItemType write
 -> commit
 -> reconnect/verify
 -> next ItemType
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

A DB2 backfill and IBM-CM API assignment are not one distributed transaction. Partial-success conditions are surfaced explicitly instead of hidden.

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
