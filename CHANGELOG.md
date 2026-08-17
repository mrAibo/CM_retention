# Changelog

All notable changes to `cm-retention` are documented here.

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
