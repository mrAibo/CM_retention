# Changelog

All notable changes to `cm-retention` are documented here.

## 0.4.0

Database-neutral existing-item backfill with Oracle support:

- normal IBM CM SDK operations remain available on DB2- and Oracle-backed IBM Content Manager 8.7 library servers
- direct `--backfill` now supports both DB2 and Oracle 19c
- added a small `BackfillDialect` abstraction instead of scattering database conditionals through the workflow
- DB2 keeps the existing timestamp arithmetic (`CREATETS + N YEAR/MONTH/WEEK/DAY`)
- Oracle follows IBM-style interval literals (`CREATETS + INTERVAL 'N' YEAR/MONTH/DAY`); WEEK is converted to days and interval leading precision is emitted when more than two digits are required
- Oracle interval values requiring more than nine leading digits fail closed instead of generating invalid SQL
- Oracle plans use `CURRENT_TIMESTAMP` and pre-write probes use `ROWNUM = 1`; DB2 keeps `CURRENT TIMESTAMP` and `FETCH FIRST 1 ROW ONLY`
- JDBC database type is detected from `BACKFILL_JDBC_URL` (`jdbc:db2:` or `jdbc:oracle:`), or can be fixed with `BACKFILL_DB_TYPE=db2|oracle`
- auto mode refuses ambiguous simultaneous `DB2_JDBC_URL` and `ORACLE_JDBC_URL` configuration
- introduced preferred neutral settings: `BACKFILL_JDBC_URL`, `BACKFILL_USER`, `BACKFILL_PASSWORD`, `BACKFILL_SCHEMA`, `BACKFILL_JDBC_JAR`
- existing DB2 installations remain backward compatible with `DB2_DATABASE`, `DB2_JDBC_URL`, `DB2_USER`, `DB2_PASSWORD`, `DB2_SCHEMA`, and `DB2_JDBC_JAR`
- Oracle-specific aliases (`ORACLE_JDBC_URL`, `ORACLE_USER`, `ORACLE_PASSWORD`, `ORACLE_SCHEMA`, `ORACLE_JDBC_JAR`) are accepted, while `BACKFILL_*` is the recommended interface
- Oracle backfill deliberately requires an explicit JDBC URL instead of guessing listener/service information from the CM alias
- launcher reads `ORACLE_HOME` from `.env` and can discover `ojdbc8.jar` under `ORACLE_HOME/jdbc/lib`, IBM/WAS locations, or an explicit `BACKFILL_JDBC_JAR`
- a missing direct-JDBC driver configuration is enforced for `--backfill`/`doctor` but no longer blocks ordinary CM-SDK commands
- DB2 and Oracle JDBC driver class loading is selected dynamically; no Oracle classes are compile-time dependencies
- all Policy/Root fingerprints, two-phase validation, post-COMMIT RC6 safety, residual-NULL verification, sequential batch semantics, and no-direct-delete guarantees remain unchanged
- single-item and batch backfill dry-runs report the selected database before any write
- added Oracle-specific 1y/5y/10y policy templates because IBM CM uses Oracle calendaring syntax for AUTO_DELETE schedules while DB2 uses cron syntax
- pure self-test now covers DB2 and Oracle JDBC detection, interval SQL/precision, current-timestamp syntax, one-row probes, and generated UPDATE/plan SQL without opening a database connection
- runtime `.env.example`, build fallback configuration, runtime installation text, README, operations guide, troubleshooting, and backfill documentation cover both DB2 and Oracle
- bumped runtime/package version to `0.4.0`

## 0.3.5

Safety and fast-path hardening after the native batch refactor:

- added immutable Policy fingerprints covering retention/expiration semantics, schedule, delete limits and force-checkin
- added immutable Root fingerprints covering ItemTypeID, ComponentTypeID, SegmentID and generated ICMUT root table
- backfill now revalidates Policy/Root/ItemType state before DB2 UPDATE, after DB2 COMMIT before policy assignment, and during final verification
- a stale Policy/Root detected before the DB2 write is refused with exit code `5`
- a stale Policy/Root detected after a committed changed-row backfill blocks policy assignment and surfaces exit code `6`
- split the detailed backfill statistics plan from the Phase-2 write preflight; Phase 2 no longer repeats the full aggregate ICMUT scan
- Phase-2 preflight uses fresh metadata plus a fail-fast `NULL CREATETS` existence check
- `--file` Phase 1 now loads the ItemType metadata collection once and resolves exact names from an in-memory map instead of one retrieveEntity round-trip per line
- `requirePolicy()` now directly retrieves the named Policy; missing Policy remains exit code `4`
- `policies` now loads Policy and ItemType collections in bulk and calculates assignment counts in memory
- single-item `assign ITEMTYPE POLICY --backfill` now runs through one native JVM and the same guarded BackfillWorkflow as batch mode
- batch/single backfill output now reports phase, ItemType and DB2/CM verification timings in seconds
- added pure `selftest` regression checks for age parsing, template detection, Policy/Root fingerprints, generated `CREATETS` SQL and timing units
- `build.sh` automatically runs the pure self-test before packaging; runtime bundle includes `tests/selftest.sh`
- removed the undocumented `CM_RETENTION_POLICY_PROPERTIES` template-path override; use `--properties FILE`, automatic readable-file syntax, or the standard `ret-policy.properties`
- policy and ItemType detail output now labels auto-delete maximum duration explicitly as seconds
- no parallel DB2/CM writes and no chunked backfill were introduced; writes remain sequential/fail-fast pending real timing measurements
- bumped runtime/package version to `0.3.5`

## 0.3.4

Native single-JVM batch performance update:

- `assign --file` and `unassign --file` now run through one native Java batch runtime instead of starting one JVM per ItemType
- Phase 1 still validates every ItemType before the first mutation
- Phase 2 remains sequential, fail-fast and deliberately non-atomic; no concurrent writes were introduced
- the target policy is resolved once during Phase 1 instead of once per ItemType process
- the CM session used for validation is discarded before Phase 2, while the existing reconnect/persisted-state verification after every CM write remains intact
- `--backfill --file` reuses one DB2 JDBC connection across the batch instead of reconnecting for every plan/apply/verify step
- backfill plan statistics are now calculated with one aggregate SELECT instead of seven independent COUNT queries per root table
- final backfill verification no longer rebuilds the complete statistics plan and performs only the required assignment/root/NULL verification
- stale-plan protection now also detects changes to the ItemType assignment and critical backfill semantics between Phase 1 and the actual write
- no parallel DB2 UPDATE or IBM CM write execution is enabled; bounded parallel planning can be considered separately after production measurements
- bumped runtime/package version to `0.3.4`

## 0.3.3

Automatic properties-template detection for policy creation:

- `cm-retention create FILE.properties` now auto-detects a readable properties template and internally normalizes it to the existing `--properties FILE` workflow
- the shorthand also works when normal create flags come before or after the template, for example `create --dry-run profiles/auto-delete-5y.properties`
- auto-detection is deliberately conservative: the file must end in `.properties`, exist, be a regular file, and be readable
- the shorthand accepts the template as the only positional create argument; ambiguous forms such as `create FILE.properties 5y` are rejected with exit code `2`
- explicit `--properties FILE` remains supported and is required when positional POLICY/AGE overrides are desired
- classic `create POLICY AGE` behavior is unchanged
- `create --help` now surfaces the shorthand syntax
- corrected `auto-delete.max-duration` documentation and create-plan output: IBM CM interprets the value in seconds (`120` = 120 seconds), not minutes
- bumped runtime/package version to `0.3.3`

## 0.3.2

Self-contained policy-template workflow:

- added required `RET_POLICY_NAME` to policy properties templates
- `create --properties FILE` now creates a policy without positional POLICY/AGE arguments
- policy name is read from `RET_POLICY_NAME`; expiration age is read from `expiration.age`
- positional `POLICY` and `AGE` remain supported and override the selected template values
- an explicitly selected properties file without `RET_POLICY_NAME` is rejected
- default `ret-policy.properties` is now a complete `AUTO_DELETE_1Y` template
- added reusable templates under `profiles/` for 1-year, 5-year and 10-year AUTO_DELETE policies
- build copies policy profiles into `build/profiles/` and packages them into the no-Git runtime bundle
- updated help, README and operations documentation for template-first policy creation
- bumped runtime/package version to `0.3.2`

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
