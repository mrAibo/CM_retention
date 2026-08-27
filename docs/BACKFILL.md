# Existing-item backfill before policy assignment

This document describes the explicit `--backfill` workflow in `cm-retention 0.3.4`.

## Purpose

Assigning an expiration policy to an IBM Content Manager ItemType does not retroactively populate expiration metadata for already existing root rows. `--backfill` is the opt-in workflow for existing rows that do not yet have retention/auto-delete dates.

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

## Correct SQL semantics

For eligible rows the tool performs the equivalent of:

```sql
UPDATE ICMADMIN.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

The creation timestamp column is:

```text
CREATETS
```

not `ICM$CREATETS`.

For a one-year policy:

```text
Formula : ICM$AUTODELETEDATE = CREATETS + 1 YEAR
```

The duration is read from the selected IBM CM policy and is not hard-coded.

Examples:

```text
1 YEAR
6 MONTHS
52 WEEKS
365 DAYS
```

`ICM$RETENTIONDATE` remains NULL because this backfill supports only policies where retention itself is disabled and expiration/AUTO_DELETE is enabled.

## Supported policy type

`--backfill` accepts only policies with:

```text
Retention type     FIXED_TIME
Retention enabled  false
Expiration enabled true
Expiration action  AUTO_DELETE
Expiration period  > 0
```

Event-driven, retention-enabled, non-AUTO_DELETE and unsupported time-unit cases are refused.

## Root table resolution

The user never supplies an `ICMUT...` table name.

The tool resolves the root component from CM metadata using the ItemType ID and `PARENTCOMPTYPEID=0`, then derives:

```text
ICMUT<COMPONENTTYPEID><SEGMENTID>
```

The generated identifier is validated before use.

## Dry-run output

Example:

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

`Immediately expired after` is critical: those rows receive an auto-delete date already in the past and can become eligible for AUTO_DELETE after policy assignment.

Since 0.3.4 these seven plan counters are calculated by one aggregate SELECT per root table instead of seven independent COUNT queries. This keeps the same output and safety checks while avoiding repeated full scans on large ItemTypes.

## Safety rules

1. Only rows where both `ICM$RETENTIONDATE` and `ICM$AUTODELETEDATE` are NULL are changed.
2. Existing retention/auto-delete dates are never overwritten.
3. NULL `CREATETS` causes refusal before update.
4. A different currently assigned policy causes refusal.
5. Re-running the same backfill is idempotent for rows already updated.
6. DB2 UPDATE is committed and verified before IBM CM policy assignment begins.
7. If eligible NULL rows remain after the update, policy assignment does not start and exit code 6 is returned.
8. After assignment, CM reconnect/persisted-state verification checks the actual ItemType state; final DB2 verification checks that no eligible NULL rows remain.
9. Multi-segment cases currently fail closed rather than updating only one segment.
10. If DB2 backfill completed but assignment/final verification is not clean, the overall command returns exit code 6.
11. Batch mode checks the ItemType assignment and critical root/policy semantics again immediately before each write; a stale plan is refused.

## Ordering and partial-success behavior

```text
plan/count
  -> confirm or dry-run
  -> DB2 UPDATE
  -> DB2 COMMIT
  -> verify no eligible NULL rows remain
  -> IBM CM policy assignment
  -> reconnect/persisted-state verification
  -> final DB2 + policy verification
```

DB2 backfill and IBM CM API assignment are not one distributed transaction. Therefore a failure after DB2 COMMIT can leave a committed backfill without a completed policy assignment.

This is surfaced with exit code 6. Re-running the same `--backfill` command is the intended recovery path after reviewing the underlying cause.

## Existing same policy

If the ItemType already uses the requested policy, `--backfill` remains allowed as a recovery operation for residual NULL rows.

If a different policy is assigned, the backfill is refused.

## Batch backfill

File:

```text
# itemtypes.txt
AM
INVOICE
CONTRACT
```

Dry-run:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
```

Interactive execution:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill
```

Automation:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --yes
```

All ItemTypes are fully planned/validated before the first mutation. Actual execution remains sequential, fail-fast and non-atomic.

### Batch performance model since 0.3.4

The complete `--file` workflow runs in one JVM instead of launching separate Java processes for every ItemType and every backfill stage.

For backfill batches:

- one `BackfillService` instance reuses one DB2 JDBC connection across Phase 1 and Phase 2
- each Phase-1 root table needs one aggregate statistics query instead of seven COUNT queries
- final verification performs only the required assignment/root/NULL checks instead of rebuilding the complete statistics plan
- CM validation is shared inside one JVM; after validation the CM session is deliberately discarded before the write phase
- the existing reconnect-based persisted-state verification after each CM write remains enabled

No parallel DB2 UPDATEs are used. Sequential writes are intentional to avoid multiplying transaction-log, I/O and lock pressure on large CM root tables.

## DB2 configuration

Normal non-backfill commands do not need additional DB2 settings.

Optional `.env` values:

```dotenv
DB2_DATABASE=LSDB
DB2_JDBC_URL=jdbc:db2:LSDB
DB2_USER=icmadmin
DB2_PASSWORD=<password>
DB2_SCHEMA=ICMADMIN
DB2_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

Defaults:

- `DB2_DATABASE` -> `CM_DATABASE`
- `DB2_USER` -> `CM_USER`
- `DB2_PASSWORD` -> `CM_PASSWORD`
- `DB2_SCHEMA` -> `ICMADMIN`

If the CM alias is not a usable DB2 alias, configure a JDBC URL explicitly, for example:

```dotenv
DB2_JDBC_URL=jdbc:db2://dbhost.example:50000/LSDB
```

The DB2 user needs SELECT access to the relevant CM metadata/root tables and UPDATE permission for the target root table.

## Recommended production procedure

1. Run `status` against the intended environment.
2. Inspect the target policy.
3. Run the exact `--backfill --dry-run` command.
4. Review root table, `CREATETS` formula, eligible count and immediately-expired count.
5. Ensure backup/change controls are in place.
6. Run the real command.
7. Check the return code.
8. Re-run the dry-run and inspect the ItemType/policy.
9. Review IBM CM/DB2 logs if exit code 6 or another warning occurred.
