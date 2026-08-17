# Changelog

All notable changes to `cm-retention` are documented here.

## 0.2.0

Admin CLI and safety refactor:

- replaced the normal two-level command tree with compact top-level commands (`status`, `policies`, `policy`, `itemtypes`, `itemtype`, `create`, `assign`, `unassign`, `delete`, `doctor`)
- added a simple interactive admin mode when the binary is run without arguments in a TTY
- missing identifiers can be selected interactively; interactive selection supports exact and unambiguous prefix matching
- automation remains deterministic: missing arguments fail in non-TTY mode and scripted identifiers require exact names
- changed create happy path to `create POLICY AGE`; advanced scheduler/limit overrides remain available only when needed
- added plan-before-mutation output to create, assign, unassign and delete
- interactive writes now require explicit `y` with conservative `[y/N]` prompts
- `--yes` remains the non-interactive confirmation mechanism
- added `--dry-run` for fully validated, non-mutating write previews
- parser now rejects unknown and duplicate options/flags instead of silently accepting irrelevant options
- added `status` and two-stage `doctor` diagnostics
- create and delete now verify the resulting policy state after commit
- retained assign/unassign reconnect + persisted-state verification and exit code `6`
- added stale-plan protection: current assignment/usage is re-checked immediately before mutation
- kept idempotent assign/unassign no-op behavior
- kept 0.1.x command forms as deprecated compatibility aliases
- split entry point, CLI, configuration/parser, and IBM-CM service into small source modules
- improved the interactive installer: hidden password input, mode `0600`, build and connection/status test
- bumped JAR implementation version to `0.2.0`

## 0.1.2

- Itemtype `assign` and `unassign` now verify the persisted state after an IBM CM API error.
- Added exit code `6` for the case where the requested state is already persisted but IBM CM reports a secondary error afterward.
- This makes partial-success situations explicit instead of reporting a simple failure or a false clean success.

## 0.1.1

- `policy list` now displays the number of assigned itemtypes and lists their names below each used policy.
- Build output suppresses the irrelevant optional classpath warning for missing `pdq.jar`.

## 0.1.0

Initial version:

- connection test
- itemtype list/show
- policy list/show/usage
- create/delete fixed-time auto-delete policies
- assign/unassign policy to itemtype
- `.env` based credential handling
- `--yes` gate for all write operations
