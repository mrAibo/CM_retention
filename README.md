# CM Retention

`cm-retention` is a small administration CLI for **IBM Content Manager Enterprise Edition 8.7** retention and expiration policies.

Current version: **0.3.5**

The project intentionally stays narrow: Java 8, the IBM CM SDK already installed on the server, no GUI, no external CLI framework, and no additional runtime dependencies beyond the existing IBM CM / DB2 runtime.

## Main capabilities

- list and inspect retention policies and their assigned ItemTypes
- list and inspect ItemTypes
- create fixed-time `AUTO_DELETE` policies
- create policies directly from reusable `.properties` templates
- automatically recognize a readable `.properties` file after `create`
- keep multiple policy templates under `profiles/`
- assign and unassign policies
- process multiple ItemTypes with `--file` in one native Java batch runtime
- run single-item `--backfill` in one native Java runtime
- preview writes with `--dry-run`
- optionally backfill existing objects before assignment with `--backfill`
- guard backfill with Policy/Root fingerprints before and after DB2 COMMIT
- print phase/item timings for batch and backfill workflows
- run pure regression checks with `selftest`
- load policy defaults from `ret-policy.properties`
- show connection/runtime status and run diagnostics with `doctor`

The tool does **not** directly delete documents and does not invoke `deleteExpiredItems()` itself.

---

# CLI

```text
cm-retention
cm-retention status
cm-retention doctor
cm-retention selftest

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

The `ITEMTYPES` column shows how many ItemTypes currently use each policy. Since 0.3.5 this view obtains the policy collection and ItemType collection in bulk and calculates the usage counts in memory instead of doing per-policy usage round-trips.

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

The ItemType names are sorted alphabetically. `Auto-delete max. duration` is displayed explicitly in **seconds** in both policy and ItemType detail output.

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

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --dry-run
bin/cm-retention create profiles/auto-delete-5y.properties
bin/cm-retention create profiles/auto-delete-5y.properties --yes
```

The file can also follow normal flags:

```bash
bin/cm-retention create --dry-run profiles/auto-delete-5y.properties
```

The tool recognizes the argument as a template only when it ends with `.properties`, exists, is a regular file and is readable. The shorthand is internally normalized to:

```bash
bin/cm-retention create --properties profiles/auto-delete-5y.properties
```

The explicit form remains fully supported.

## Ambiguity rule

Automatic template mode accepts the template as the **only positional create argument**.

Valid:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --schedule "0 4 * * *"
```

Deliberately rejected:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties 10y
```

To override the policy name or age positionally, use the explicit form:

```bash
bin/cm-retention create TEMP_POLICY 10y \
  --properties profiles/auto-delete-5y.properties \
  --dry-run
```

## Default template and precedence

Because the default `ret-policy.properties` contains both `RET_POLICY_NAME` and `expiration.age`, this also works:

```bash
bin/cm-retention create --dry-run
bin/cm-retention create
```

Values are resolved in this order:

```text
explicit CLI POLICY / AGE / options
        > selected properties template
        > default ret-policy.properties
        > built-in fallback
```

There is no hidden environment-variable override for the policy-template path in 0.3.5; use the explicit `--properties FILE` form (or the automatic readable-file shorthand) when selecting another template.

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

`--max-duration 180` means 180 seconds (3 minutes).

Force-checkin can be changed for one create operation:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --no-force-checkin
```

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

Typical runtime paths:

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

The launcher refuses insecure `.env` permissions. For separate environments prefer `.env.test` / `.env.prod` and use `--env` explicitly.

---

# Build and self-test

Run on a compatible IBM CM 8.7 build host:

```bash
./build.sh
```

The version is read from `src/CmRetention.java`. Since 0.3.5 the build compiles all sources and then automatically runs a **pure regression self-test** before creating the JAR. The self-test loads the installed SDK classes but does **not** log in to Content Manager and does **not** connect to DB2.

It covers the age parser, template auto-detection, Policy/Root fingerprints, generated backfill SQL (including the required `CREATETS` column) and timing units.

A successful build includes:

```text
Running self-test...
Self-test: OK (... checks)
...
Self-test:      passed
```

For version 0.3.5 the build creates:

```text
build/cm-retention.jar
build/cm-retention-0.3.5.jar
build/.version
build/ret-policy.properties
build/profiles/auto-delete-1y.properties
build/profiles/auto-delete-5y.properties
build/profiles/auto-delete-10y.properties
build/cm-retention-0.3.5-runtime.tar.gz
build/SHA256SUMS-0.3.5
```

Verify:

```bash
cat build/.version
bin/cm-retention version
bin/cm-retention selftest
```

Expected:

```text
0.3.5
cm-retention 0.3.5
Self-test: OK (... checks)
```

The runtime bundle also contains `tests/selftest.sh`.

## Deployment without Git

Copy `build/cm-retention-0.3.5-runtime.tar.gz` to the target server, extract it, create a protected `.env`, then run:

```bash
bin/cm-retention version
bin/cm-retention selftest
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

Since 0.3.5 the entire single-item workflow runs in **one JVM** using the same guarded Java workflow as batch backfill. The old shell sequence of separate plan/apply/assign/verify JVMs is no longer used.

For eligible rows the equivalent DB2 operation remains:

```sql
UPDATE ICMADMIN.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

The physical creation timestamp column is `CREATETS`, not `ICM$CREATETS`.

## Backfill safety fingerprints

Phase 1 records immutable snapshots of:

- the target Policy (type, retention/expiration settings, period/unit, action, scheduler and delete limits)
- the physical root identity (`ItemTypeID`, `ComponentTypeID`, `SegmentID`, generated `ICMUT...` table)

The snapshots are checked again:

1. immediately before the DB2 update (mismatch -> exit `5`, no backfill write),
2. after DB2 COMMIT and before policy assignment (mismatch after a changed-row backfill -> exit `6`, **policy is not assigned**),
3. during final verification (mismatch -> exit `6`).

This closes the race where a policy with the same name could be edited while the backfill workflow was running.

## Phase-2 fast path

The detailed dry-run/Phase-1 plan still calculates all report counters with one aggregate SELECT. Phase 2 no longer repeats that full aggregate scan. Before the UPDATE it checks fresh metadata/fingerprints and uses only a fail-fast existence query for an eligible row with `NULL CREATETS`.

The DB2 UPDATE is then committed and residual NULL rows are verified before policy assignment.

Real execution:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

The output includes timings for preflight, DB2, post-COMMIT guard, CM assignment and final verification.

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

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --yes
```

The complete `--file` workflow runs inside **one JVM**. In 0.3.5 Phase 1 additionally loads the complete ItemType metadata collection once and resolves requested names from an in-memory map instead of issuing one `retrieveEntity()` call per input line. The target policy is resolved once and fingerprinted.

With `--backfill`, one DB2 JDBC connection is reused across the batch. Each root-table plan uses one aggregate SELECT. Before Phase 2 the CM validation session is deliberately discarded so writes do not rely on potentially stale metadata.

Phase 2 remains **sequential, fail-fast and deliberately non-atomic**. No parallel UPDATE/write execution is enabled. If one ItemType fails, processing stops and earlier successful changes remain committed.

The output includes:

```text
Batch mode (native Java runtime)
  Runtime   : single JVM
...
Phase 1 timing: ... sec
Item timing: ... sec
Phase 2 timing: ... sec
Total timing  : ... sec
```

These measurements should be used before deciding whether any future bounded parallel planning is necessary.

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
resolve -> read current state -> validate -> plan -> dry-run/confirmation
 -> mutate -> commit -> reconnect/verify persisted state
```

Batch mode adds:

```text
one JVM
 -> validate ALL ItemTypes using one bulk metadata snapshot
 -> one confirmation
 -> discard validation CM session
 -> sequential per-ItemType fresh-state write
 -> commit/reconnect/verify
 -> next ItemType
```

Backfill in 0.3.5 uses:

```text
detailed plan + Policy/Root fingerprints
 -> confirm
 -> fresh ItemType/Policy/Root guard
 -> cheap NULL-CREATETS preflight
 -> DB2 UPDATE
 -> DB2 COMMIT + residual verification
 -> fresh post-COMMIT Policy/Root/ItemType guard
 -> policy assignment
 -> reconnect
 -> final Policy/Root/assignment/DB2 verification
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
| `5` | unsafe/conflicting operation refused before a relevant write |
| `6` | verification warning/failure or partial-success condition after persistence may have occurred |

Scripts should always inspect the return code, especially `6`.

---

# Documentation

- [Detailed operations guide](DOKUMENTATION.md)
- [Backfill workflow](docs/BACKFILL.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Metadata repair notes](docs/METADATA_REPAIR.md)
- [Changelog](CHANGELOG.md)
