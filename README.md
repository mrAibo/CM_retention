# CM Retention

`cm-retention` is a small administration CLI for **IBM Content Manager Enterprise Edition 8.7** retention and expiration policies.

Current version: **0.4.0**

The project intentionally stays narrow: Java 8, the IBM CM SDK already installed on the server, no GUI, no external CLI framework, and no direct document-delete command.

## Database support

Normal commands use the IBM CM SDK and are database-independent from the tool's perspective:

```text
status / doctor
policies / policy
itemtypes / itemtype
create
delete
assign / unassign
--file without --backfill
```

The explicit direct-database `--backfill` workflow supports:

| Library Server database | Normal CM commands | `--backfill` |
|---|---:|---:|
| DB2 | yes | yes |
| Oracle 19c | yes | yes |

IBM Content Manager 8.7 supports Oracle 19c and requires `ojdbc8.jar` for Oracle Java API/database connectivity.

## Main capabilities

- list and inspect retention policies and their assigned ItemTypes
- list and inspect ItemTypes
- create fixed-time `AUTO_DELETE` policies
- create policies from reusable `.properties` templates
- assign/unassign one or many ItemTypes
- `--dry-run` and conservative confirmation for writes
- single-JVM `--file` batch runtime
- guarded existing-item `--backfill` before assignment
- DB2 and Oracle backfill SQL dialects
- Policy/Root fingerprints around database/CM transaction boundaries
- phase/item timings
- pure `selftest` regression checks
- runtime tarball for hosts without Git/javac

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

## Policy usage

```bash
bin/cm-retention policies
bin/cm-retention policy AUTO_DELETE_5Y
```

`policies` shows the assigned ItemType count. `policy POLICY` also lists the exact assigned ItemTypes:

```text
Assigned itemtypes:         3
  - AM
  - CONTRACT
  - INVOICE
```

`Auto-delete max. duration` is displayed in **seconds**.

---

# Policy templates

A complete supported template looks like:

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

`auto-delete.max-duration=120` means **120 seconds**.

Included templates:

```text
ret-policy.properties
profiles/auto-delete-1y.properties
profiles/auto-delete-5y.properties
profiles/auto-delete-10y.properties
```

Recommended syntax:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --dry-run
bin/cm-retention create profiles/auto-delete-5y.properties
```

A readable `.properties` file is detected automatically when it is the only positional create argument. Explicit `--properties FILE` remains supported and is required when positional POLICY/AGE overrides are used.

Precedence:

```text
explicit CLI POLICY / AGE / options
        > selected properties template
        > default ret-policy.properties
        > built-in fallback
```

---

# Installation and build

Typical runtime paths:

```text
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Build on a compatible CM host:

```bash
./build.sh
```

The build compiles all Java sources and runs `SelfTestMain` before creating artifacts. The self-test loads the SDK classes but does **not** log in to CM and does **not** open a DB2/Oracle JDBC connection.

Version 0.4.0 produces:

```text
build/cm-retention.jar
build/cm-retention-0.4.0.jar
build/.version
build/ret-policy.properties
build/profiles/...
build/cm-retention-0.4.0-runtime.tar.gz
build/SHA256SUMS-0.4.0
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
0.4.0
cm-retention 0.4.0
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

IBM documents that applying a retention policy to an existing ItemType affects new items/new versions; existing items require SQL or a custom API procedure to populate `ICM$RETENTIONDATE` / `ICM$AUTODELETEDATE` metadata.

Use the explicit opt-in:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

The logical update remains:

```sql
UPDATE <SCHEMA>.<ICMUT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

The physical creation timestamp column is `CREATETS`, not `ICM$CREATETS`.

## DB2 formula

```text
ICM$AUTODELETEDATE = CREATETS + 1 YEAR
```

DB2 uses `CURRENT TIMESTAMP` and `FETCH FIRST 1 ROW ONLY` in the relevant plan/preflight queries.

## Oracle formula

```text
ICM$AUTODELETEDATE = CREATETS + NUMTOYMINTERVAL(1, 'YEAR')
```

Oracle generation uses:

```text
YEAR  -> NUMTOYMINTERVAL(N, 'YEAR')
MONTH -> NUMTOYMINTERVAL(N, 'MONTH')
WEEK  -> NUMTODSINTERVAL(N*7, 'DAY')
DAY   -> NUMTODSINTERVAL(N, 'DAY')
```

The Oracle plan uses `CURRENT_TIMESTAMP`; the fail-fast one-row probe uses `ROWNUM = 1`.

## Safety

Backfill accepts only:

```text
FIXED_TIME
retention disabled
expiration enabled
AUTO_DELETE
positive YEAR/MONTH/WEEK/DAY period
```

It also:

- never overwrites existing retention/auto-delete dates
- refuses eligible rows with NULL `CREATETS`
- refuses a different already-assigned policy
- derives and validates the physical `ICMUT...` root table automatically
- rejects unsupported multi-segment roots
- fingerprints Policy and root metadata
- revalidates before UPDATE, after database COMMIT, and during final verification
- verifies residual NULL rows before starting policy assignment
- returns exit `6` for persisted/partial-success states after a relevant COMMIT
- keeps batch writes sequential and fail-fast

The database UPDATE and IBM CM API assignment are not one distributed transaction.

Detailed procedure: [docs/BACKFILL.md](docs/BACKFILL.md).

---

# Direct database configuration for `--backfill`

Normal non-backfill commands do not require these settings.

## Preferred neutral DB2 configuration

```dotenv
BACKFILL_DB_TYPE=db2
BACKFILL_JDBC_URL=jdbc:db2:LSDB
BACKFILL_USER=icmadmin
BACKFILL_PASSWORD=CHANGE_ME
BACKFILL_SCHEMA=ICMADMIN
BACKFILL_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

Existing `DB2_*` settings remain backward compatible. With no `BACKFILL_*`/DB2 override at all, the legacy default remains:

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

`BACKFILL_DB_TYPE=auto` (or omitting it) detects DB2/Oracle from the JDBC prefix. A configured type/URL mismatch is rejected.

Oracle aliases `ORACLE_JDBC_URL`, `ORACLE_USER`, `ORACLE_PASSWORD`, `ORACLE_SCHEMA`, `ORACLE_JDBC_JAR` are accepted, but `BACKFILL_*` is preferred.

The launcher can locate `ojdbc8.jar` under `$ORACLE_HOME/jdbc/lib` or common IBM/WAS locations. Explicit `BACKFILL_JDBC_JAR` is the most deterministic configuration.

---

# Batch mode

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

The complete file workflow runs in one JVM. With backfill, one JDBC connection is reused across the batch. Phase 1 validates all ItemTypes before the first mutation. Phase 2 remains sequential, fail-fast, and non-atomic.

The backfill batch header reports the selected database:

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
| `6` | verification warning/failure or partial-success state after persistence may have occurred |

Scripts should always inspect the return code, especially `6`.

---

# Documentation

- [Detailed operations guide](DOKUMENTATION.md)
- [Backfill workflow: DB2 + Oracle](docs/BACKFILL.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Changelog](CHANGELOG.md)
