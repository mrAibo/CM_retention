# CM Retention

`cm-retention` is a small administration CLI for **IBM Content Manager Enterprise Edition 8.7** retention and expiration policies.

Current version: **0.4.4**

The project intentionally stays narrow: Java 8, the IBM CM SDK already installed on the server, no GUI, no external CLI framework, and no direct document-delete command.

## Database support

The IBM CM SDK operations work with both DB2- and Oracle-backed Library Servers. The explicit direct-database `--backfill` workflow supports both database families as well.

| Library Server database | Read/assign/unassign | Policy create/delete | `--backfill` |
|---|---:|---:|---:|
| DB2 | yes | yes | yes |
| Oracle 19c | yes | yes | yes |

There is one important database-specific detail for **policy creation**: IBM CM uses different automatic-delete schedule syntax depending on the Library Server database:

- **DB2:** UNIX cron, for example `0 2 * * *`
- **Oracle:** Oracle calendaring syntax, for example `FREQ=DAILY;BYHOUR=2;BYMINUTE=0;BYSECOND=0;`

For that reason the repository contains ready-to-use variants for both platforms:

```text
profiles/auto-delete-1y.properties
profiles/auto-delete-5y.properties
profiles/auto-delete-10y.properties

profiles/auto-delete-1y-oracle.properties
profiles/auto-delete-5y-oracle.properties
profiles/auto-delete-10y-oracle.properties
```

The expiration/retention semantics are identical; only the automatic-delete schedule string differs. Do not use a DB2 cron schedule on an Oracle Library Server or an Oracle calendaring expression on DB2.

## Main capabilities

- list and inspect retention policies and their assigned ItemTypes
- list and inspect ItemTypes
- create fixed-time `AUTO_DELETE` policies
- create policies from reusable `.properties` templates
- assign and unassign policies
- process many ItemTypes with `--file` in one JVM
- use a comma-separated ItemType list as a compact batch shortcut
- preview writes with `--dry-run`
- guarded existing-item `--backfill` before assignment
- direct backfill against DB2 or Oracle
- adaptive chunked COMMITs for large DB2 backfills
- bounded catch-up for rows created concurrently during long DB2 backfills
- Policy/Root fingerprints around database/CM transaction boundaries
- verified-warning continuation for IBM CM secondary errors in batch mode
- independent post-batch final verification in a second JVM/fresh CM session
- automatic batch audit logs and confirmed-mismatch retry files
- phase and ItemType timings
- pure `selftest` regression checks
- runtime tarball for hosts without Git or `javac`

The tool does **not** call `deleteExpiredItems()` and does not directly delete documents.

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

cm-retention assign ITEM1,ITEM2,ITEM3 POLICY
cm-retention unassign ITEM1,ITEM2,ITEM3

cm-retention assign --file ITEMTYPES.txt POLICY
cm-retention unassign --file ITEMTYPES.txt

cm-retention assign ITEMTYPE POLICY --backfill
cm-retention assign ITEM1,ITEM2,ITEM3 POLICY --backfill
cm-retention assign --file ITEMTYPES.txt POLICY --backfill
```

Run without arguments in a terminal for the small interactive admin menu.

## Inspecting policy usage

```bash
bin/cm-retention policies
bin/cm-retention policy AUTO_DELETE_5Y
```

`policies` shows the number of assigned ItemTypes. `policy POLICY` also lists the exact ItemTypes:

```text
Assigned itemtypes:         3
  - AM
  - CONTRACT
  - INVOICE
```

`Auto-delete max. duration` is displayed in **seconds**.

## Changing an already-assigned AUTO_DELETE policy

Treat live changes to an already-assigned AUTO_DELETE policy conservatively, especially changes to:

```text
expiration.age
auto-delete.schedule
auto-delete.commit-count
auto-delete.max-items
auto-delete.max-duration
auto-delete.force-checkin
```

On IBM CM 8.7 the per-ItemType automatic-delete scheduler/task state may not be rebuilt consistently just because the shared Policy object was edited. The safe operational pattern is:

```text
unassign affected ItemTypes
-> change/recreate the policy
-> assign affected ItemTypes again
-> use --backfill only when existing-item dates must be populated/repaired
```

For semantic changes such as a different expiration age, creating a new Policy is usually clearer than mutating a heavily used one. Existing `ICM$AUTODELETEDATE` values are not silently recalculated by a normal unassign/assign.

---

# Policy templates

A policy template contains the policy semantics and automatic-delete settings:

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

`auto-delete.max-duration=120` means **120 seconds**, not 120 minutes.

## DB2 create example

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --dry-run
bin/cm-retention create profiles/auto-delete-5y.properties
```

The DB2 templates use cron syntax:

```properties
auto-delete.schedule=0 2 * * *
```

## Oracle create example

```bash
bin/cm-retention create profiles/auto-delete-5y-oracle.properties --dry-run
bin/cm-retention create profiles/auto-delete-5y-oracle.properties
```

The Oracle templates use Oracle calendaring syntax:

```properties
auto-delete.schedule=FREQ=DAILY;BYHOUR=2;BYMINUTE=0;BYSECOND=0;
```

An explicit CLI schedule still overrides the template, but the supplied value must use the syntax required by the target Library Server database.

A readable `.properties` file is automatically recognized when it is the only positional `create` argument. Explicit `--properties FILE` remains supported and is required when positional POLICY/AGE overrides are used.

Precedence remains:

```text
explicit CLI POLICY / AGE / options
        > selected properties template
        > default ret-policy.properties
        > built-in fallback
```

The root `ret-policy.properties` and the non-suffixed profiles remain DB2-oriented for backward compatibility. On Oracle, select one of the `*-oracle.properties` templates or provide an Oracle schedule explicitly.

---

# Installation and build

Typical runtime paths:

```text
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Build on a compatible CM 8.7 host:

```bash
./build.sh
```

The build compiles all Java sources and runs `SelfTestMain` before creating artifacts. The self-test loads IBM CM SDK classes but does **not** log in to Content Manager and does **not** open a DB2/Oracle JDBC connection.

Version 0.4.4 produces:

```text
build/cm-retention.jar
build/cm-retention-0.4.4.jar
build/.version
build/ret-policy.properties
build/profiles/*.properties
build/cm-retention-0.4.4-runtime.tar.gz
build/SHA256SUMS-0.4.4
```

Verify:

```bash
cat build/.version
bin/cm-retention version
bin/cm-retention selftest
bin/cm-retention doctor
```

Expected version:

```text
0.4.4
cm-retention 0.4.4
```

The runtime bundle contains no IBM SDK, JDBC driver, or credentials.

---

# Normal assignment

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --dry-run
bin/cm-retention assign AM AUTO_DELETE_1Y
```

Normal assignment does not retroactively populate expiration metadata for existing items.

---

# Existing-item backfill

IBM documents that applying a system-controlled retention policy to an existing ItemType does not retroactively populate retention/expiration metadata for existing items. `--backfill` is the explicit opt-in for this migration.

Always start read-only:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

Single-item dry-run prints the selected direct database explicitly before the detailed plan, for example:

```text
Backfill database           : Oracle
```

Real execution:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

The logical update is:

```sql
UPDATE <SCHEMA>.<ICMUT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

The physical creation timestamp column is `CREATETS`, not `ICM$CREATETS`.

## DB2 backfill SQL

A one-year policy is generated as:

```text
ICM$AUTODELETEDATE = CREATETS + 1 YEAR
```

DB2 uses `CURRENT TIMESTAMP` for the immediate-expiration plan calculation and `FETCH FIRST 1 ROW ONLY` for the fail-fast probe.

### Large DB2 backfills (0.4.3+)

Large DB2 backfills are split into bounded UPDATE/COMMIT chunks instead of one very large transaction. This avoids exhausting the active DB2 transaction log (`SQLCODE=-964`). The chunked path keeps the same NULL guards and therefore remains idempotent on retry.

The initial chunk size is at most 250,000 rows. If DB2 reports `-964`, only the current uncommitted chunk is rolled back and retried with a smaller chunk size. ItemType assignment, Policy fingerprint and Root fingerprint are checked after every committed chunk; a mismatch stops with exit `6` and prevents policy assignment.

Starting with 0.4.4, a short final chunk is no longer assumed to mean that the table is stable. Long-running backfills can overlap with normal document creation while the target policy is still unassigned. The chunker therefore recounts the remaining NULL rows and performs up to 20 bounded catch-up passes before assignment. Policy assignment starts only after the residual count reaches zero. If rows with `CREATETS=NULL` remain, or concurrent writes prevent a stable zero state within the bound, the workflow stays fail-closed with exit `6`.

## Oracle backfill SQL

The Oracle dialect follows the interval-literal form used by IBM's CM SQL examples. A one-year policy is generated as:

```text
ICM$AUTODELETEDATE = CREATETS + INTERVAL '1' YEAR
```

Generation rules:

```text
YEAR  -> INTERVAL 'N' YEAR
MONTH -> INTERVAL 'N' MONTH
WEEK  -> INTERVAL 'N*7' DAY
DAY   -> INTERVAL 'N' DAY
```

Oracle interval literals have a default leading precision of two digits. The generator therefore adds an explicit precision whenever necessary, for example:

```text
300 months -> INTERVAL '300' MONTH(3)
52 weeks   -> INTERVAL '364' DAY(3)
365 days   -> INTERVAL '365' DAY(3)
```

The Oracle plan uses `CURRENT_TIMESTAMP`; its one-row fail-fast probe uses `ROWNUM = 1`.

## Backfill safety

Backfill accepts only:

```text
FIXED_TIME
retention disabled
expiration enabled
AUTO_DELETE
positive YEAR/MONTH/WEEK/DAY period
```

It also:

- never overwrites an existing `ICM$RETENTIONDATE` or `ICM$AUTODELETEDATE`
- refuses eligible rows with NULL `CREATETS`
- refuses a different already-assigned policy
- resolves the physical root table from CM metadata rather than CLI input
- rejects unsupported multi-segment roots
- fingerprints Policy and root metadata
- revalidates before UPDATE, after database COMMIT, and during final verification
- for chunked DB2 backfills, revalidates assignment/Policy/Root after each chunk COMMIT
- catches up bounded concurrent rows before policy assignment
- verifies residual NULL rows before policy assignment
- returns exit `6` for persisted/partial-success conditions after a relevant COMMIT
- keeps batch writes sequential

The direct database UPDATE and IBM CM API assignment are **not one distributed transaction**.

Detailed procedure: [docs/BACKFILL.md](docs/BACKFILL.md).

---

# Direct database configuration for `--backfill`

Normal non-backfill commands do not require these settings. A missing explicitly configured direct-JDBC driver is enforced for `--backfill`/`doctor`, but does not block ordinary CM-SDK commands.

## Preferred DB2 configuration

```dotenv
BACKFILL_DB_TYPE=db2
BACKFILL_JDBC_URL=jdbc:db2:LSDB
BACKFILL_USER=icmadmin
BACKFILL_PASSWORD=CHANGE_ME
BACKFILL_SCHEMA=ICMADMIN
BACKFILL_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

A network URL can also be used:

```dotenv
BACKFILL_JDBC_URL=jdbc:db2://dbhost.example:50000/LSDB
```

Existing `DB2_*` settings remain backward compatible. With no `BACKFILL_*`/DB2 override, the historical default remains:

```text
jdbc:db2:<CM_DATABASE>
```

## Oracle 19c configuration

```dotenv
ORACLE_HOME=/u01/app/oracle/product/19.0.0/dbhome_1
BACKFILL_DB_TYPE=oracle
BACKFILL_JDBC_URL=jdbc:oracle:thin:@//dbhost.example:1521/LSDB
BACKFILL_USER=icmconct
BACKFILL_PASSWORD=CHANGE_ME
BACKFILL_SCHEMA=ICMADMIN
BACKFILL_JDBC_JAR=/u01/app/oracle/product/19.0.0/dbhome_1/jdbc/lib/ojdbc8.jar
```

Oracle requires an explicit JDBC URL. The tool deliberately does not infer listener/service information from `CM_DATABASE`.

`BACKFILL_DB_TYPE=auto` (or omitting it when a URL is supplied) detects DB2/Oracle from the JDBC prefix. A configured type/URL mismatch is rejected.

Oracle aliases `ORACLE_JDBC_URL`, `ORACLE_USER`, `ORACLE_PASSWORD`, `ORACLE_SCHEMA`, and `ORACLE_JDBC_JAR` are accepted, but `BACKFILL_*` is preferred.

`ORACLE_HOME` can be read from `.env`; the launcher can locate `ojdbc8.jar` under `$ORACLE_HOME/jdbc/lib` or common IBM/WAS locations. An explicit `BACKFILL_JDBC_JAR` is the most deterministic production configuration.

---

# Batch mode

Example file:

```text
# itemtypes.txt
AM
INVOICE
CONTRACT
```

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --dry-run
bin/cm-retention unassign --file itemtypes.txt --yes
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --yes
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --yes
```

For short ad-hoc batches, 0.4.4 also accepts comma-separated exact ItemType names:

```bash
bin/cm-retention assign MNDPDM_DOC_05,ITEMTYPE2,ITEMTYPE3 AUTO_DELETE_1Y --backfill --yes
bin/cm-retention assign ITEMTYPE1,ITEMTYPE2 AUTO_DELETE_1Y --yes
bin/cm-retention unassign ITEMTYPE1,ITEMTYPE2 --yes
```

The comma form is normalized by the launcher into the same protected batch workflow as `--file`; it is not a separate mutation implementation. Therefore it receives the same Phase-1 validation, sequential/fail-fast writes, verified-warning handling, audit log, independent Phase-3 verifier and retry file. Empty or duplicate entries are rejected. When whitespace around commas is desired, quote the ItemType-list argument so the shell passes it as one argument.

The complete mutation phase runs in one JVM. With backfill, one JDBC connection is reused across the batch. Phase 1 validates every ItemType before the first mutation. Phase 2 remains sequential and non-atomic.

## Verified secondary IBM CM warnings in 0.4.1

Older CM metadata can produce a secondary error after IBM CM has already persisted the requested assignment change, for example:

```text
DGL0303A: Invalid parameter
DKAttrDefICM::getViewOperator() opCode : [-1]
```

`CmService` reconnects and verifies the requested persisted state before raising `OperationWarning`. Starting with 0.4.1, batch mode treats that state separately from a real failure:

```text
clean success     -> continue
verified warning  -> report, continue, final RC 6
real/uncertain failure -> stop immediately
```

Example Phase-2 summary:

```text
Batch complete: 217/217 item types reached a verified final state.
Clean success     : 180
Verified warnings : 37
Warning itemtypes : AM, ...
```

The warning list is capped in the Phase-2 summary so large legacy environments do not produce another huge block; each warning is still printed at the ItemType where it occurred.

For `--file --backfill`, continuation is allowed only when the secondary assignment warning is followed by a successful complete final Policy/Root/assignment/residual-NULL verification. A backfill RC6 whose final state is uncertain still stops the mutation batch immediately.

## Independent final verifier and audit log in 0.4.2

Every batch invocation (`--file` or comma-list shortcut) gets its own audit log. By default it is written below:

```text
<application-home>/logs/
```

The location can be overridden in `.env`:

```dotenv
CM_RETENTION_LOG_DIR=/var/log/cm-retention
```

The log directory is restricted to the runtime user and each log records the version, timestamp, user/host, exact command, selected `.env` path, complete batch output, verifier output and final return-code summary. For a comma-list shortcut, the original command and normalized batch command are both retained. Credentials are not written to the log.

For a real write batch, after Phase 2 has started the launcher starts **a second Java process** (`BatchVerifyMain`). That process creates a new CM session and rereads every exact ItemType from the original batch input. It does not reuse the mutation JVM or its metadata/session cache.

Expected state:

```text
unassign batch              -> Retention policy must be empty
assign batch POLICY         -> Retention policy must equal POLICY exactly
```

Example:

```text
Phase 3/3: independent final verification
  Runtime   : new JVM / fresh CM session
  Operation : unassign
  Expected  : -
  Item types: 217

Final verification summary
  Verified OK         : 214
  State mismatches    : 3
  Verification errors : 0
```

If confirmed mismatches exist, the verifier prints each expected/actual state and automatically creates a `*-retry.txt` containing **only those confirmed mismatches**. The file can be used directly with the same batch command, for example:

```bash
bin/cm-retention unassign --file logs/cm-retention-batch-...-unassign-retry.txt --yes
```

If an ItemType cannot be read reliably because the SDK itself errors, its state is marked **unknown** and it is deliberately excluded from the retry file. This avoids blindly mutating an ItemType whose final state was not established.

The independent verifier also runs after a mutation batch that stopped part-way through. This makes the log/retry file an exact view of what still differs from the requested end state instead of forcing the administrator to reconstruct the processed prefix manually.

A dry-run, validation failure, or interactive cancellation before Phase 2 is logged but does not run the final verifier.

Return-code combination:

```text
batch clean + verifier clean       -> 0
batch clean + verifier not clean   -> 6
verified secondary warning(s)      -> 6
true mutation/runtime failure      -> original failure RC; verifier still reports current state
```

The backfill batch header reports the selected database, for example:

```text
Batch mode (native Java runtime)
  Backfill  : yes
  Database  : Oracle
  Runtime   : single JVM
```

---

# Exit codes

| Code | Meaning |
|---:|---|
| `0` | success / no change / successful dry-run |
| `2` | CLI, configuration, properties, JDBC-driver, or preflight error |
| `3` | IBM CM / database runtime error |
| `4` | ItemType or policy not found |
| `5` | unsafe/conflicting operation refused before a relevant write |
| `6` | verified IBM CM secondary warning, independent final-verifier mismatch/error, verification failure, or partial-success state after persistence may have occurred |

A batch may therefore process its complete input and still return `6` when the requested final state was not independently clean or one or more verified IBM CM secondary warnings occurred. Scripts should inspect both the audit summary and the return code.

---

# Documentation

- [Detailed operations guide](DOKUMENTATION.md)
- [Backfill workflow: DB2 + Oracle](docs/BACKFILL.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Changelog](CHANGELOG.md)
