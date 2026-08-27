# Existing-item backfill before policy assignment

This document describes the explicit `--backfill` workflow in `cm-retention 0.4.0`.

## Purpose

Applying a system-controlled retention/expiration policy to an existing IBM Content Manager ItemType does not retroactively populate retention/expiration metadata for existing items. IBM documents that existing items require SQL or a custom API procedure. `--backfill` is the explicit opt-in workflow for that case.

Normal assignment:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y
```

Backfill followed by assignment:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

Always start with:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

The single-item and `--file` workflows share one guarded Java execution engine. Writes remain sequential.

## Supported databases

`cm-retention 0.4.0` supports direct existing-item backfill against:

- IBM Db2
- Oracle 19c as supported by IBM Content Manager 8.7

All normal non-backfill commands continue to use the IBM CM SDK and do not depend on the direct-database dialect.

The database is selected from the backfill JDBC URL:

```text
jdbc:db2:...     -> DB2
jdbc:oracle:...  -> Oracle
```

It can also be fixed explicitly with:

```dotenv
BACKFILL_DB_TYPE=db2
```

or:

```dotenv
BACKFILL_DB_TYPE=oracle
```

A configured type that conflicts with the JDBC URL is rejected.

## Common logical SQL

For eligible rows the logical operation is:

```sql
UPDATE <SCHEMA>.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

The physical creation timestamp column is `CREATETS`, **not** `ICM$CREATETS`.

IBM documents `CreateTS`, `ICM$RetentionDate`, and `ICM$AutoDeleteDate` on the `ICMUTnnnnnsss` component-root table.

## DB2 SQL dialect

Examples:

```sql
CREATETS + 1 YEAR
CREATETS + 6 MONTHS
CREATETS + 52 WEEKS
CREATETS + 365 DAYS
```

The detailed plan compares calculated dates with:

```sql
CURRENT TIMESTAMP
```

The Phase-2 fail-fast existence probe ends with:

```sql
FETCH FIRST 1 ROW ONLY
```

## Oracle SQL dialect

Oracle timestamp arithmetic is generated without NLS-dependent string/date conversion:

```sql
CREATETS + NUMTOYMINTERVAL(1, 'YEAR')
CREATETS + NUMTOYMINTERVAL(6, 'MONTH')
CREATETS + NUMTODSINTERVAL(364, 'DAY')   -- 52 weeks
CREATETS + NUMTODSINTERVAL(365, 'DAY')
```

The detailed plan uses:

```sql
CURRENT_TIMESTAMP
```

The fail-fast existence probe uses:

```sql
AND ROWNUM = 1
```

`WEEK` is converted to an exact number of days (`amount * 7`). No user-provided SQL fragment is accepted.

## Supported policy type

`--backfill` accepts only policies with:

```text
Retention type     FIXED_TIME
Retention enabled  false
Expiration enabled true
Expiration action  AUTO_DELETE
Expiration period  > 0
```

Supported units are YEAR, MONTH, WEEK, and DAY. Event-driven, retention-enabled, non-AUTO_DELETE, and unsupported-unit cases fail closed.

## Root-table resolution

The user never supplies an `ICMUT...` table name. The tool resolves the root component using the ItemType ID and CM library-server metadata:

```text
ICMSTCOMPDEFS
ICMSTITEMTYPEDEFS
```

It then derives:

```text
ICMUT<COMPONENTTYPEID><SEGMENTID>
```

The generated identifier and schema are strictly validated before insertion into SQL. Multi-segment cases continue to fail closed.

## Detailed dry-run plan

Example DB2 output:

```text
Existing-item backfill plan

Item type                 : AM
Current policy            : -
Target policy             : AUTO_DELETE_1Y
ItemType ID               : 1238
Root component ID         : 1468
Component SegmentID       : 1
Root table                : ICMUT01468001
Expiration                : 1 YEAR
Formula                   : ICM$AUTODELETEDATE = CREATETS + 1 YEAR

Root rows total           : 432
Missing both dates        : 417
Backfillable rows         : 417
NULL create timestamp     : 0
Immediately expired after : 381
Already auto-delete dated : 15
Retention date already set: 0
```

Equivalent Oracle formula output is, for example:

```text
Formula                   : ICM$AUTODELETEDATE = CREATETS + NUMTOYMINTERVAL(1, 'YEAR')
```

`Immediately expired after` is critical: those rows receive a date already in the past and can become eligible for AUTO_DELETE after policy assignment.

The seven report counters are calculated by **one aggregate SELECT per root table**.

## Phase-2 fast path

The detailed Phase-1/dry-run aggregate is not repeated immediately before the write. Phase 2 freshly revalidates ItemType/Policy/root metadata and only probes whether an eligible row has `NULL CREATETS`.

DB2 conceptually uses:

```sql
SELECT 1
FROM <SCHEMA>.<ROOT_TABLE>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NULL
FETCH FIRST 1 ROW ONLY;
```

Oracle uses:

```sql
SELECT 1
FROM <SCHEMA>.<ROOT_TABLE>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NULL
  AND ROWNUM = 1;
```

If such a row exists, no UPDATE is started.

## Policy and root fingerprints

Phase 1 records immutable fingerprints of the selected Policy and physical root identity.

Policy fingerprint:

```text
name
retention type/enabled/period/unit
expiration enabled/period/unit/action
auto-delete schedule
commit count
max items
max duration
force check-in
```

Root fingerprint:

```text
ItemTypeID
ComponentTypeID
SegmentID
ICMUT table
```

They are checked again:

1. immediately before the database UPDATE;
2. after database COMMIT and before policy assignment;
3. during final verification.

If the mismatch occurs before the database write, the command returns exit `5`. If database rows were already committed and a later guard fails, the command returns exit `6` and does **not** start policy assignment.

## Safety rules

1. Only rows where both `ICM$RETENTIONDATE` and `ICM$AUTODELETEDATE` are NULL are changed.
2. Existing retention/auto-delete dates are never overwritten.
3. NULL `CREATETS` causes refusal before UPDATE.
4. A different currently assigned policy causes refusal.
5. Re-running the same backfill is idempotent for rows already updated.
6. Policy/root fingerprints are revalidated around the direct-database/CM boundary.
7. UPDATE is committed and residual NULL rows are verified before IBM CM policy assignment begins.
8. If eligible NULL rows remain after UPDATE, policy assignment does not start and exit `6` is returned.
9. A policy/root change after a committed backfill blocks assignment and returns exit `6`.
10. After assignment, a fresh CM session verifies actual persisted state.
11. Multi-segment cases fail closed.
12. Writes remain sequential; no parallel direct-database UPDATEs are used.
13. The tool never calls `deleteExpiredItems()` and never directly deletes documents.

## Transaction boundary

```text
detailed plan + fingerprints
  -> confirm/dry-run
  -> fresh ItemType/Policy/Root guard
  -> cheap NULL-CREATETS probe
  -> direct database UPDATE
  -> database COMMIT
  -> residual-NULL verification
  -> fresh post-COMMIT ItemType/Policy/Root guard
  -> IBM CM policy assignment
  -> CM reconnect/persisted-state verification
  -> final Policy/Root/assignment/database verification
```

The direct database transaction and IBM CM API assignment are not one distributed transaction. A failure after database COMMIT can therefore leave backfilled rows without a completed policy assignment. This is deliberately exposed with exit `6`.

## Configuration: preferred neutral form

### DB2

```dotenv
BACKFILL_DB_TYPE=db2
BACKFILL_JDBC_URL=jdbc:db2:LSDB
BACKFILL_USER=icmadmin
BACKFILL_PASSWORD=<password>
BACKFILL_SCHEMA=ICMADMIN
BACKFILL_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

Type-4 example:

```dotenv
BACKFILL_JDBC_URL=jdbc:db2://dbhost.example:50000/LSDB
```

For backward compatibility, the old `DB2_DATABASE`, `DB2_JDBC_URL`, `DB2_USER`, `DB2_PASSWORD`, `DB2_SCHEMA`, and `DB2_JDBC_JAR` names remain accepted. If no backfill database settings exist at all, the legacy DB2 default `jdbc:db2:<CM_DATABASE>` remains in effect.

### Oracle 19c

```dotenv
ORACLE_HOME=/u01/app/oracle/product/19.0.0/dbhome_1
BACKFILL_DB_TYPE=oracle
BACKFILL_JDBC_URL=jdbc:oracle:thin:@//dbhost.example:1521/LSDB
BACKFILL_USER=icmconct
BACKFILL_PASSWORD=<password>
BACKFILL_SCHEMA=ICMADMIN
BACKFILL_JDBC_JAR=/u01/app/oracle/product/19.0.0/dbhome_1/jdbc/lib/ojdbc8.jar
```

IBM Content Manager 8.7 requires `ojdbc8.jar` for Oracle users. The launcher can find it automatically under `$ORACLE_HOME/jdbc/lib` or known IBM/WAS locations; an explicit `BACKFILL_JDBC_JAR` is the most deterministic option.

Oracle requires an explicit JDBC URL. The tool does not manufacture a listener/service string from `CM_DATABASE`.

Oracle aliases `ORACLE_JDBC_URL`, `ORACLE_USER`, `ORACLE_PASSWORD`, `ORACLE_SCHEMA`, and `ORACLE_JDBC_JAR` are also accepted, but `BACKFILL_*` is preferred.

The direct-database user needs SELECT access to the relevant CM metadata/root tables and UPDATE permission on target root tables.

## Single-item runtime

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

The real execution output explicitly identifies the selected database:

```text
Applying existing-item backfill
  Database         : Oracle
  Item type        : AM
  ...
```

## Batch backfill

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --yes
```

The header includes `Database: DB2` or `Database: Oracle`. One `BackfillService` reuses one JDBC connection across the batch. Phase 1 still validates every ItemType before the first mutation; Phase 2 remains sequential, fail-fast, and non-atomic.

## Self-test

`./build.sh` runs `SelfTestMain` before packaging. The test does not log in to CM and does not open DB2/Oracle connections. It verifies, among other things:

- DB2 and Oracle JDBC URL detection
- DB2 YEAR/MONTH/WEEK/DAY duration syntax
- Oracle `NUMTOYMINTERVAL` / `NUMTODSINTERVAL` generation
- Oracle WEEK-to-DAY conversion
- DB2 `CURRENT TIMESTAMP`
- Oracle `CURRENT_TIMESTAMP`
- DB2 `FETCH FIRST 1 ROW ONLY`
- Oracle `ROWNUM = 1`
- `CREATETS` rather than the incorrect `ICM$CREATETS`
- UPDATE NULL guards

Manual test:

```bash
bin/cm-retention selftest
```

## Recommended production procedure

1. Run `selftest`, `doctor`, and `status` after deployment.
2. Inspect the target policy with `policy POLICY`.
3. Confirm the intended direct database URL/schema/user in the protected `.env`.
4. Run the exact `--backfill --dry-run` command.
5. Review root table, generated formula, eligible count, immediately-expired count, and selected database.
6. Ensure database backup/change controls are in place.
7. Run the real command.
8. Inspect the return code; treat `6` as a possible persisted/partial-success condition.
9. Re-run dry-run/status checks and inspect ItemType/policy state.
10. Review IBM CM and DB2/Oracle logs if any warning/error occurred.
