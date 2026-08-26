# Changelog

All notable changes to `cm-retention` are documented here.

## 0.3.1

Backfill correctness and policy-defaults update:

- fixed the root-table creation timestamp column from the incorrect `ICM$CREATETS` identifier to the real `CREATETS` column in all backfill COUNT, immediate-expiration and UPDATE SQL
- corrected the displayed backfill formula to `ICM$AUTODELETEDATE = CREATETS + <duration>`
- added `ret-policy.properties` as the central policy-defaults file
- added `--properties FILE` to override the default policy properties for one create operation
- policy precedence is now `CLI > --properties FILE > ret-policy.properties > built-in fallback`
- `expiration.age` can provide the age when positional AGE is omitted
- added strict rejection of unknown property keys and unsupported semantic policy modes
- changed the AUTO_DELETE default `force-checkin` to `true` (`Einchecken vor Loeschen erzwingen`)
- added `--no-force-checkin` as an explicit one-command override; `--force-checkin` remains available
- build now copies `ret-policy.properties` into `build/` and into the no-Git runtime bundle
- build continues to create `build/cm-retention.jar`, immutable `build/cm-retention-0.3.1.jar`, `.version`, runtime TAR.GZ and SHA-256 checksums
- bumped runtime/package version to `0.3.1`

## 0.3.0

Existing-item backfill workflow:

- added explicit `assign ITEMTYPE POLICY --backfill`
- added `assign --file ITEMTYPES.txt POLICY --backfill` for guarded multi-item backfill + assignment
- backfill is performed before policy assignment and only updates rows where both `ICM$RETENTIONDATE` and `ICM$AUTODELETEDATE` are `NULL`
- root component/table is resolved automatically from `ICMSTCOMPDEFS` + `ICMSTITEMTYPEDEFS`; table names are never accepted from the CLI
- dry-run reports total root rows, backfillable rows, NULL create timestamps, already dated rows and rows that would be immediately expired
- backfill refuses non-FIXED_TIME policies, retention-enabled policies, non-AUTO_DELETE policies and non-positive expiration periods
- backfill refuses a switch from a different already assigned policy to avoid mixed expiration dates
- SQL update is idempotent because only rows with both dates NULL are modified
- DB2 backfill commits before the IBM CM policy assignment, then verifies no eligible NULL rows remain
- final verification checks both the policy assignment and residual NULL rows
- if backfill was committed but assignment/final verification is not clean, the launcher returns exit code `6`
- added optional DB2 settings (`DB2_JDBC_URL`, `DB2_DATABASE`, `DB2_USER`, `DB2_PASSWORD`, `DB2_SCHEMA`, `DB2_JDBC_JAR`)
- DB2 JDBC credentials remain in the protected `.env`; no password CLI option was added

## 0.2.1

Batch/deployment update:

- added `--file` batch mode for `assign` and `unassign`
- batch files use one exact ItemType name per line, ignore blanks/comments, and reject duplicates
- all batch entries are validated via normal Java dry-run before the first mutation
- real batch execution is sequential, fail-fast, and deliberately non-atomic
- retained the single-item reconnect/persisted-state verification and exit code `6` for every batch item
- `build.sh` derives the version from `CmRetention.VERSION`
- build creates both `cm-retention.jar` and immutable `cm-retention-<VERSION>.jar`
- build writes `build/.version`
- build creates `cm-retention-<VERSION>-runtime.tar.gz` for deployment to servers without Git or `javac`
- build creates SHA-256 checksums when `sha256sum` is available

## 0.2.0

Admin CLI and safety refactor:

- compact top-level commands (`status`, `policies`, `policy`, `itemtypes`, `itemtype`, `create`, `assign`, `unassign`, `delete`, `doctor`)
- interactive admin mode for TTY use
- exact/prefix interactive selection while scripted identifiers remain exact
- `create POLICY AGE` happy path
- plan-before-mutation output
- conservative `[y/N]` confirmation
- `--yes` for automation
- `--dry-run`
- strict unknown/duplicate option rejection
- status and doctor diagnostics
- reconnect/persisted-state verification and exit code `6`
- stale-plan protection
- idempotent assign/unassign
- legacy 0.1.x aliases

## 0.1.2

- assign/unassign verify persisted state after IBM CM API errors
- added exit code `6` for persisted-change/secondary-error cases

## 0.1.1

- policy list displays assigned ItemType count
- build suppresses irrelevant optional classpath warning for missing `pdq.jar`

## 0.1.0

Initial version:

- connection test
- ItemType list/show
- policy list/show/usage
- create/delete fixed-time auto-delete policies
- assign/unassign policy to ItemType
- `.env` credential handling
- `--yes` gate for writes
