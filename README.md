# CM Retention

`cm-retention` is a small administration CLI for **IBM Content Manager Enterprise Edition 8.7** retention and expiration policies.

Current version: **0.4.1**

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
- preview writes with `--dry-run`
- guarded existing-item `--backfill` before assignment
- direct backfill against DB2 or Oracle
- Policy/Root fingerprints around database/CM transaction boundaries
- verified-warning continuation for IBM CM secondary errors in batch mode
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

cm-retention assign --file ITEMTYPES.txt POLICY
cm-retention unassign --file ITEMTYPES.txt

cm-retention assign ITEMTYPE POLICY --backfill
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

Version 0.4.1 produces:

```text
build/cm-retention.jar
build/cm-retention-0.4.1.jar
build/.version
build/ret-policy.properties
build/profiles/*.properties
build/cm-retention-0.4.1-runtime.tar.gz
build/SHA256SUMS-0.4.1
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
0.4.1
cm-retention 0.4.1
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

IBM documents that applying a system-controlled retention policy to an existing ItemType does not retroactively populate the policy metadata for existing items. `--backfill` is the explicit opt-in for this migration.

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

The complete file workflow runs in one JVM. With backfill, one JDBC connection is reused across the batch. Phase 1 validates every ItemType before the first mutation. Phase 2 remains sequential and non-atomic.

## Verified secondary IBM CM warnings in 0.4.1

Older CM metadata can produce a secondary error after IBM CM has already persisted the requested assignment change, for example:

```text
DGL0303A: Invalid parameter
DKAttrDefICM::getViewOperator() opCode : [-1]
```

`CmService` already reconnects and verifies the requested persisted state before raising `OperationWarning`. Starting with 0.4.1, batch mode treats that state separately from a real failure:

```text
clean success     -> continue
verified warning  -> report, continue, final RC 6
real/uncertain failure -> stop immediately
```

Example final summary:

```text
Batch complete: 217/217 item types reached a verified final state.
Clean success     : 180
Verified warnings : 37
Warning itemtypes : AM, ...
Result            : requested state was verified, but IBM CM reported secondary errors; returning exit 6.
```

The warning list is capped in the final summary so large legacy environments do not produce another huge block; each warning is still printed at the ItemType where it occurred.

For `--file --backfill`, continuation is allowed only when the secondary assignment warning is followed by a successful complete final Policy/Root/assignment/residual-NULL verification. A backfill RC6 whose final state is uncertain still stops the batch immediately.

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
| `6` | verified IBM CM secondary warning, verification warning/failure, or partial-success state after persistence may have occurred |

A batch may therefore process its complete file and still return `6` when every requested state was verified but one or more IBM CM secondary warnings occurred. Scripts should inspect both the summary and the return code.

---

# Documentation

- [Detailed operations guide](DOKUMENTATION.md)
- [Backfill workflow: DB2 + Oracle](docs/BACKFILL.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Changelog](CHANGELOG.md)
