# Changelog

All notable changes to `cm-retention` are documented here.

## 0.4.4

- Add comma-separated inline ItemType batches for assign/unassign; they reuse the existing audited `--file` workflow.
- Add bounded DB2 catch-up passes for rows created during long backfills.
- Harden inline parsing, SQL schema validation, and JDBC close diagnostics.

Inline-batch convenience and concurrent-write backfill hardening after production validation on an active multi-million-row ItemType:

- `assign ITEM1,ITEM2,... POLICY` and `unassign ITEM1,ITEM2,...` are now accepted as compact batch shortcuts
- comma-list syntax is normalized by the launcher into the existing native `--file` batch workflow rather than creating a second mutation implementation
- comma-list batches therefore keep the same Phase-1 validation, sequential/fail-fast writes, verified-warning handling, audit log, independent Phase-3 verifier, retry file, and `--backfill` semantics
- temporary inline-batch files are created with restrictive permissions and removed automatically; the audit log retains both the original command and normalized batch command
- DB2 `--backfill` now always uses the bounded executor, including small/resume runs, so a retry after a previously large partial backfill receives the same residual-row catch-up protection
- after the first short DB2 chunk, residual rows are re-counted and up to 20 bounded catch-up passes handle rows created concurrently while the policy is still unassigned
- policy assignment still does not start until the pre-assignment residual count reaches zero; continuous write pressure or non-backfillable `CREATETS=NULL` rows remain fail-closed with exit `6`
- after policy assignment, final verification now performs one last guarded catch-up if rows appeared during the final pre-assign/assign window, then re-verifies the persisted policy and residual count
- Phase 3 for `--backfill` now verifies both the exact target policy and the direct-database residual state; an ItemType with the policy assigned but remaining NULL retention/auto-delete metadata is no longer counted as `Verified OK`
- Phase-3 backfill verification uses an existence probe first and performs a full COUNT only when a residual mismatch actually exists
- self-test covers DB2 catch-up sizing in addition to existing chunk syntax / SQL0964C detection
- bumped runtime/package version to `0.4.4`

## 0.4.3

Large-DB2-backfill and verifier-classification hardening after the first production run against multi-million-row ItemTypes:

- DB2 `--backfill` automatically switches to bounded chunked UPDATE/COMMIT execution above 10,000 planned rows instead of one very large transaction
- chunks start at up to 250,000 rows; if DB2 returns SQL0964C / SQLCODE `-964` (transaction log full), only the current transaction is rolled back and the chunk size is halved down to a 1,000-row floor before retrying
- chunk SQL keeps the same NULL guards and uses the DB2 11.5 searched-UPDATE fetch clause, so already committed rows are excluded automatically on resume
- each committed chunk reports progress and is followed by a fresh ItemType assignment / Policy fingerprint / Root fingerprint safety guard
- any non-recoverable failure after one or more chunk COMMITs returns exit `6`, blocks policy assignment, and leaves the same backfill command safely resumable
- small DB2 backfills retain the existing one-transaction path; Oracle behavior is unchanged
- final batch verification now distinguishes the ItemType where a fail-fast batch actually stopped from later ItemTypes that were never attempted
- verifier summaries classify `failed at stop`, `not attempted`, and any unexpected `post-write mismatch` separately instead of making every remaining ItemType look like an independent failure
- large mismatch groups are capped in console output while the generated retry file still contains every confirmed ItemType that has not reached the requested state
- bumped runtime/package version to `0.4.3`

## 0.4.2

Independent batch-verification and audit hardening:

- every `assign --file` / `unassign --file` invocation now gets a timestamped audit log under `<application-home>/logs` by default
- `CM_RETENTION_LOG_DIR` can override the audit-log directory; the launcher creates the directory/log with restrictive permissions and never writes credentials
- after any real batch reaches Phase 2, the launcher starts `BatchVerifyMain` in a **second JVM with a fresh IBM CM session** so the final check cannot reuse mutation-session/cache state
- the independent verifier rereads every exact ItemType from the original file and checks the requested end state (`unassign` => no policy, `assign` => exact target policy)
- final verification also runs after a batch stops part-way through, giving an explicit view of which requested ItemTypes still differ from the target state
- confirmed state mismatches are printed with expected/actual policy and written to a generated `*-retry.txt`
- retry files contain only confirmed mismatches; ItemTypes whose SDK read fails are reported as state-unknown and deliberately excluded from automatic retry input
- a mutation batch that otherwise returns success but fails independent final verification is promoted to exit code `6`
- true mutation/runtime failures retain their original exit code; the independent verifier is diagnostic and does not mask the primary failure
- dry-runs, validation-only failures, and interactive cancellations before Phase 2 are logged but do not run the final verifier
- self-test now covers final-verifier policy-state comparison semantics
- bumped runtime/package version to `0.4.2`

## 0.4.1

Verified-warning batch hardening for IBM CM 8.7 legacy metadata cases:

- `assign --file` and `unassign --file` no longer stop when IBM CM reports a secondary `OperationWarning` after the requested persisted state has already been confirmed by reconnect verification
- the observed `DGL0303A: DKAttrDefICM::getViewOperator() opCode : [-1]` case is therefore reported per ItemType and the batch continues to later ItemTypes instead of requiring one restart per legacy ItemType
- true runtime failures, stale-state failures, and any condition where the requested final state cannot be verified remain fail-fast
- batch output distinguishes `clean success`, `verified warnings`, and real failures; warning ItemTypes are summarized compactly
- a batch that reaches the requested final state for every ItemType but encounters one or more verified secondary IBM CM errors completes the full file and returns exit code `6`
- `--file --backfill` uses the same rule only when the assignment warning is followed by a successful final Policy/Root/assignment/residual-NULL verification; otherwise it still stops with exit `6`
- single-item assign/unassign/backfill behavior remains conservative: a verified secondary IBM CM warning still returns exit `6`
- no automatic SQL repair of `ICMSTCOMPVIEWATTRS.VIEWOPERATOR=-1` was introduced; internal CM metadata repair remains a separate controlled maintenance task
- operational guidance now treats changes to an already-assigned AUTO_DELETE policy (especially its schedule) conservatively: use controlled unassign/reassign or a new policy so per-ItemType automatic-delete tasks are rebuilt consistently
- self-test now covers compact batch-warning summary formatting
- bumped runtime/package version to `0.4.1`

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
- added immutable Root fingerprints covering ItemTypeID, ComponentTypeID, SegmentID and generated root table
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
- corrected `auto-delete.max-duration` documentation and create-plan output: IBM CM interprets the value in seconds (`120` = 120 seconds), not 120 minutes
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
