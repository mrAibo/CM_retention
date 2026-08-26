# Existing-item backfill before policy assignment

This document describes the explicit `--backfill` workflow introduced in `cm-retention 0.3.0`.

## Purpose

Assigning an expiration policy to an IBM Content Manager ItemType does not retroactively populate expiration metadata for already existing root rows. `--backfill` is the opt-in workflow for the specific case where existing rows have no retention/auto-delete dates yet.

The backfill does **not** change the default behavior of `assign`.

Normal assignment:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y
```

Existing-item backfill followed by assignment:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

Always start with:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

## SQL semantics

For eligible rows the tool performs the equivalent of:

```sql
UPDATE ICMADMIN.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = ICM$CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND ICM$CREATETS IS NOT NULL;
```

The duration is not hard-coded. It is read from the selected IBM CM policy, for example:

```text
1 YEAR
6 MONTHS
52 WEEKS
365 DAYS
```

`ICM$RETENTIONDATE` stays NULL because this backfill implementation intentionally supports only policies where retention itself is disabled and expiration/AUTO_DELETE is enabled.

## Supported policy type

`--backfill` accepts only a policy that is all of the following:

```text
Retention type     FIXED_TIME
Retention enabled  false
Expiration enabled true
Expiration action  AUTO_DELETE
Expiration period  > 0
```

The command refuses event-driven policies, retention-enabled policies, non-AUTO_DELETE policies and unsupported time units.

## Root table resolution

The user never supplies an `ICMUT...` table name.

The tool resolves the root component from:

```text
ICMSTCOMPDEFS
ICMSTITEMTYPEDEFS
```

using the ItemType ID returned by the IBM CM API and `PARENTCOMPTYPEID=0`, then derives the root table as:

```text
ICMUT<COMPONENTTYPEID><SEGMENTID>
```

The generated identifier is validated before it is used in SQL.

## Dry-run output

A dry-run connects to IBM CM and DB2, resolves the policy/root table and reports counts before any update:

```text
Existing-item backfill plan

Item type                 : AM
Current policy            : -
Target policy             : AUTO_DELETE_1Y
ItemType ID               : 1238
Root component ID         : 1468
Root table                : ICMUT01468001
Expiration                : 1 YEAR
Formula                   : ICM$AUTODELETEDATE = ICM$CREATETS + 1 YEAR

Root rows total           : 432
Missing both dates        : 417
Backfillable rows         : 417
NULL create timestamp     : 0
Immediately expired after : 381
Already auto-delete dated : 15
Retention date already set: 0
```

`Immediately expired after` is particularly important. Those rows receive an auto-delete date that is already in the past and therefore become eligible for IBM CM AUTO_DELETE after the policy assignment.

## Safety rules

The workflow is deliberately conservative:

1. Only rows where both `ICM$RETENTIONDATE` and `ICM$AUTODELETEDATE` are NULL are changed.
2. Rows with an existing retention or auto-delete date are never overwritten.
3. A NULL `ICM$CREATETS` causes the backfill to stop before any update.
4. A different currently assigned policy causes the backfill to stop. This prevents a silent mixture of dates derived from two different policies.
5. Re-running the same backfill is idempotent for already updated rows.
6. The DB2 update is committed and verified before the IBM CM policy assignment begins.
7. If eligible NULL rows remain after the update, policy assignment is not started and the command exits with code 6.
8. After assignment, a fresh process verifies both the assigned policy and the absence of residual NULL rows.
9. If the DB2 backfill was committed but policy assignment/final verification is not clean, the overall command returns exit code 6.

## Ordering and partial-success behavior

The workflow is intentionally:

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

DB2 backfill and the IBM CM Java API assignment are not one distributed transaction. Therefore a failure after the DB2 commit can leave a committed backfill without a completed policy assignment.

This is not hidden. The launcher reports it as a warning/exit code 6. Re-running the same `--backfill` command is the intended recovery path after the underlying cause has been reviewed.

## Existing same policy

If the ItemType already uses the requested policy, `--backfill` is still allowed. This is useful as a recovery operation for residual NULL rows.

If the ItemType uses a **different** policy, `--backfill` refuses the operation.

## Batch backfill

File format:

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

Before the first mutation the launcher runs the complete backfill plan for **every** ItemType in the file. If any entry fails validation, no batch changes are made.

The actual batch remains sequential and non-atomic. For each ItemType:

```text
DB2 backfill -> verify -> policy assignment -> final verify
```

If one ItemType fails during phase 2, the batch stops. Earlier fully successful ItemTypes remain committed. The current ItemType may already have a committed backfill if the failure happened during assignment/final verification.

## DB2 configuration

Normal non-backfill commands do not need additional DB2 settings.

For backfill, values may be added to the selected `.env`:

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
- `DB2_JDBC_URL` -> `jdbc:db2:<DB2_DATABASE>`
- `DB2_USER` -> `CM_USER`
- `DB2_PASSWORD` -> `CM_PASSWORD`
- `DB2_SCHEMA` -> `ICMADMIN`

If the IBM CM alias is not a usable DB2 database alias, configure `DB2_JDBC_URL` explicitly. A type-4 URL can also be used:

```dotenv
DB2_JDBC_URL=jdbc:db2://dbhost.example:50000/LSDB
```

The DB2 user must have the required SELECT access to the CM metadata/root table and UPDATE permission for the target root table. No credential is accepted as a command-line argument.

## JDBC driver

The launcher first uses the normal IBM CM classpath. It also checks common `db2jcc4.jar` locations. If necessary, configure the exact path:

```dotenv
DB2_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

If the driver cannot be loaded, `--backfill` stops before SQL execution.

## Recommended production procedure

1. Run `status` against the intended environment.
2. Inspect the policy with `policy NAME`.
3. Run the exact `--backfill --dry-run` command.
4. Review root table, eligible count and especially the immediately-expired count.
5. Ensure the relevant operational/backup/change controls are in place.
6. Run the real command interactively or with an approved `--yes` automation.
7. Check the return code.
8. Re-run the same command as `--dry-run`; it should show no residual eligible NULL rows and the ItemType should already use the target policy.
9. Review IBM CM logs if exit code 6 or an IBM SDK warning occurred.

## What `--backfill` does not do

- it does not overwrite existing retention/auto-delete dates
- it does not backfill retention-enabled policies
- it does not convert event-driven retention
- it does not accept arbitrary SQL or arbitrary table names
- it does not directly delete documents
- it does not invoke `deleteExpiredItems()`
