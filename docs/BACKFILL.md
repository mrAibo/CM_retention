# Existing-item backfill before policy assignment

This document describes the explicit `--backfill` workflow in `cm-retention 0.3.5`.

## Purpose

Assigning an expiration policy to an IBM Content Manager ItemType does not retroactively populate expiration metadata for already existing root rows. `--backfill` is the explicit opt-in workflow for existing rows that do not yet have retention/auto-delete dates.

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

Since 0.3.5 the single-item workflow and `--file` workflow share the same guarded Java execution engine. A single-item backfill no longer launches separate JVMs for plan/apply/assign/verify.

## Correct SQL semantics

For eligible rows the tool performs the equivalent of:

```sql
UPDATE ICMADMIN.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

The creation timestamp column is `CREATETS`, **not** `ICM$CREATETS`.

For a one-year policy:

```text
Formula : ICM$AUTODELETEDATE = CREATETS + 1 YEAR
```

The duration is read from the selected IBM CM policy and is not hard-coded. Supported DB2 duration forms include `1 YEAR`, `6 MONTHS`, `52 WEEKS` and `365 DAYS`.

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

The user never supplies an `ICMUT...` table name. The tool resolves the root component from CM metadata using the ItemType ID and `PARENTCOMPTYPEID=0`, then derives:

```text
ICMUT<COMPONENTTYPEID><SEGMENTID>
```

The generated identifier is validated before use. Multi-segment cases continue to fail closed.

## Detailed dry-run plan

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

The seven plan counters are calculated by **one aggregate SELECT per root table** rather than seven independent COUNT queries.

## Phase-2 fast path

0.3.5 deliberately separates the user-visible detailed plan from the final pre-write check.

Phase 2 does **not** rebuild the full seven-counter plan and therefore does not repeat the expensive aggregate scan immediately before UPDATE. Instead it re-reads the critical metadata and performs only a fail-fast query for an eligible row with `NULL CREATETS`:

```sql
SELECT 1
FROM ICMADMIN.<ROOT_TABLE>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NULL
FETCH FIRST 1 ROW ONLY;
```

If such a row exists, the backfill is refused before the UPDATE.

## Policy fingerprint

Phase 1 records an immutable fingerprint of the selected IBM CM policy:

```text
policy name
retention type/enabled/period/unit
expiration enabled/period/unit/action
auto-delete schedule
commit count
max items
max duration
force check-in
```

The fingerprint is compared with freshly retrieved policy metadata:

1. after validation and immediately before DB2 backfill;
2. after DB2 COMMIT and immediately before policy assignment;
3. during final verification.

This prevents a policy with the same name but changed semantics from being assigned after existing rows were calculated with the old semantics.

### Exit behavior for policy changes

If the mismatch is detected **before** a relevant DB2 write, the command fails with exit `5` and performs no backfill mutation.

If rows have already been committed to DB2 and the policy is then found changed, the workflow returns exit `6` and **does not assign the changed policy**. The committed backfill remains visible as an explicit partial-success state for review/recovery.

## Root fingerprint

Phase 1 also records:

```text
ItemTypeID
ComponentTypeID
SegmentID
root ICMUT table name
```

The root identity is revalidated before UPDATE, after DB2 COMMIT before assignment, and during final verification. A changed root mapping is therefore never silently followed.

## Safety rules

1. Only rows where both `ICM$RETENTIONDATE` and `ICM$AUTODELETEDATE` are NULL are changed.
2. Existing retention/auto-delete dates are never overwritten.
3. NULL `CREATETS` causes refusal before update.
4. A different currently assigned policy causes refusal.
5. Re-running the same backfill is idempotent for rows already updated.
6. Policy and root fingerprints are frozen during Phase 1 and revalidated around the DB2/CM boundary.
7. DB2 UPDATE is committed and residual NULL rows are verified before IBM CM policy assignment begins.
8. If eligible NULL rows remain after the update, policy assignment does not start and exit `6` is returned.
9. A policy/root change after a committed backfill blocks assignment and returns exit `6`.
10. After assignment, a fresh CM session verifies the actual assignment and policy fingerprint; DB2 verification checks residual NULL rows and root identity.
11. Multi-segment cases fail closed.
12. Writes remain sequential; no parallel DB2 UPDATEs are introduced.

## Ordering and partial-success behavior

```text
detailed plan + fingerprints
  -> confirm or dry-run
  -> fresh ItemType/Policy/Root guard
  -> cheap NULL-CREATETS preflight
  -> DB2 UPDATE
  -> DB2 COMMIT
  -> verify no eligible NULL rows remain
  -> fresh post-COMMIT ItemType/Policy/Root guard
  -> IBM CM policy assignment
  -> reconnect/persisted-state verification
  -> final Policy/Root/assignment/DB2 verification
```

DB2 backfill and IBM CM API assignment are not one distributed transaction. Therefore a failure after DB2 COMMIT can leave a committed backfill without a completed policy assignment. This is surfaced with exit `6`; the tool does not hide the condition.

## Existing same policy

If the ItemType already uses the requested policy, `--backfill` remains allowed as a recovery operation for residual NULL rows. If a different policy is assigned, backfill is refused.

## Single-item runtime

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

Both commands run one native Java workflow. Output includes plan and execution timings, e.g.:

```text
Plan timing                : 1.234 sec
Timing                    : preflight ... / DB2 ... / post-commit guard ... / CM ... / verify ... / total ...
```

## Batch backfill

File:

```text
# itemtypes.txt
AM
INVOICE
CONTRACT
```

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --yes
```

All ItemTypes are fully planned/validated before the first mutation. Actual execution remains sequential, fail-fast and non-atomic.

### Batch performance model in 0.3.5

- one JVM for the complete file workflow
- one Phase-1 bulk ItemType metadata load, then in-memory exact-name lookup
- target policy resolved/fingerprinted once during validation
- one `BackfillService` reuses one DB2 JDBC connection across Phase 1 and Phase 2
- one aggregate statistics query per Phase-1 root table
- no repeated full aggregate plan scan in Phase 2
- validation CM session deliberately discarded before writes
- reconnect-based persisted-state verification retained after CM writes
- phase and per-ItemType timings are printed

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

## Build/self-test

`./build.sh` in 0.3.5 automatically runs the pure `SelfTestMain` before packaging. Among other checks it asserts that generated backfill SQL uses `CREATETS`, never `ICM$CREATETS`, and preserves the NULL guards.

Manual test after build:

```bash
bin/cm-retention selftest
```

This does not log in to CM and does not connect to DB2.

## Recommended production procedure

1. Run `bin/cm-retention selftest` after deployment.
2. Run `status` against the intended environment.
3. Inspect the target policy with `policy POLICY`.
4. Run the exact `--backfill --dry-run` command.
5. Review root table, `CREATETS` formula, eligible count and immediately-expired count.
6. Review reported Phase-1 timing on large ItemTypes.
7. Ensure backup/change controls are in place.
8. Run the real command.
9. Check the return code; treat `6` as a possible persisted/partial-success condition.
10. Re-run dry-run/status checks and inspect ItemType/policy.
11. Review IBM CM/DB2 logs if exit `6` or another warning occurred.
