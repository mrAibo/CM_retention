# CM Retention

`cm-retention` is a small administration CLI for **IBM Content Manager Enterprise Edition 8.7** retention and expiration policies.

It intentionally stays narrow: Java 8, the IBM CM SDK already installed on the server, one launcher, no GUI, no external CLI framework and no additional runtime dependencies.

## What it does

- list retention policies and their assignments
- inspect a policy in detail
- list and inspect item types
- create fixed-time `AUTO_DELETE` expiration policies
- assign and unassign a policy to an item type
- delete an unused policy
- show environment/connection status
- run local + IBM CM diagnostics with `doctor`
- preview write operations with `--dry-run`

It does **not** delete documents directly, run `deleteExpiredItems()`, backfill existing items, or modify IBM CM system tables.

## v0.2 CLI

```text
cm-retention
cm-retention status
cm-retention policies
cm-retention policy [POLICY]
cm-retention itemtypes
cm-retention itemtype [ITEMTYPE]
cm-retention create [POLICY] [AGE]
cm-retention assign [ITEMTYPE] [POLICY]
cm-retention unassign [ITEMTYPE]
cm-retention delete [POLICY]
cm-retention doctor
```

Run without arguments for the interactive admin mode:

```text
CM Retention 0.2.0 | LSDB | icmadmin

  1  Policies
  2  Item types
  3  Create policy
  4  Assign policy
  5  Unassign policy
  6  Delete policy
  7  Status
  8  Doctor

  q  Quit
```

This is intentionally not a full-screen TUI. It is only a small prompt layer over the same scriptable command core.

## Quick examples

List policies:

```bash
bin/cm-retention policies
```

Inspect one policy:

```bash
bin/cm-retention policy AUTO_DELETE_5Y
```

Create a five-year auto-delete policy using the safe defaults:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y
```

The default policy settings are:

```text
schedule       daily 02:00 (0 2 * * *)
commit count   100
max items      5000
max duration   120 minutes
force check-in false
```

Assign it:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y
```

Preview the operation without changing IBM CM:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y --dry-run
```

For non-interactive automation, `--yes` is required:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y --yes
```

## Interactive vs. automation

The CLI deliberately treats humans and scripts differently.

**Interactive terminal**

- missing item type / policy arguments are offered as a numbered selection
- exact and unambiguous prefix matching is available while selecting
- every real write prints its plan first
- every write defaults to **No**: `[y/N]`

**non-TTY / cron / pipeline**

- all required identifiers must be supplied explicitly
- identifiers must match exactly; no prefix matching is performed
- real writes require `--yes`
- `--dry-run` does not require `--yes`

This prevents a cron job from waiting for input and keeps automation deterministic.

## Safety model

Every write follows the same model:

```text
resolve -> read current state -> validate -> print plan
        -> dry-run / confirm -> mutate -> commit -> verify persisted state
```

Important boundaries:

- policy assignment is idempotent: assigning the already active policy returns success with `No change`
- unassigning an item type without a policy is also a successful no-op
- an assigned policy cannot be deleted
- existing documents are **not** retroactively backfilled when a policy is assigned
- passwords are read from `.env` / environment, never from a command-line password option
- unknown options are rejected instead of being silently ignored
- after problematic IBM CM item-type updates the tool reconnects and verifies the actual persisted state
- exit code `6` preserves the important distinction between a clean success and a persisted change followed by a secondary IBM CM error
- the service re-checks the current assignment immediately before mutation and refuses stale plans

## Configuration

Create the local configuration:

```bash
cp .env.example .env
chmod 600 .env
```

Example:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=change-me
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

`.env` is ignored by Git and must not be committed.

### Test and production

Treat environments as separate server configurations. Do not reuse one `.env` by editing it back and forth.

Example:

```text
.env.test
.env.prod
```

```bash
chmod 600 .env.test .env.prod

bin/cm-retention --env .env.test status
bin/cm-retention --env .env.prod status
```

A write can then be targeted explicitly:

```bash
bin/cm-retention --env .env.test assign AM AUTO_DELETE_1Y --dry-run
```

## Build

Requirements:

- IBM Content Manager 8.7 installed locally
- Java 8 / `javac`
- `${IBMCMROOT}/lib/cmbicmsdk81.jar`

Build:

```bash
./build.sh
```

Output:

```text
build/cm-retention.jar
```

## Installer

For an interactive first installation:

```bash
./install.sh
```

The installer prompts for IBM CM root, Java home, database, user and password, writes `.env` with mode `0600`, builds the tool and runs `status` as a connection test.

The password is read with hidden terminal input and is not passed in argv.

## Status and doctor

Normal overview:

```bash
bin/cm-retention status
```

Deeper diagnostics:

```bash
bin/cm-retention doctor
```

`doctor` starts with launcher checks (configuration permissions, Java, IBM CM SDK, native library/config paths, application JAR) and then performs Java/IBM CM connection, policy-API and item-type-API checks.

## Advanced create options

The happy path is deliberately short:

```bash
bin/cm-retention create RET_10Y 10y
```

Only unusual environments normally need overrides:

```bash
bin/cm-retention create RET_10Y 10y \
  --schedule "0 4 * * *" \
  --commit-count 200 \
  --max-items 10000 \
  --max-duration 180
```

Show the complete create help:

```bash
bin/cm-retention create --help
```

## Exit codes

| Code | Meaning |
|---:|---|
| `0` | success / no change / successful dry-run |
| `2` | CLI, configuration, preflight or confirmation error |
| `3` | IBM CM / runtime operation error |
| `4` | requested item type or policy not found |
| `5` | unsafe/conflicting operation, e.g. policy already exists, is still assigned, or the displayed state changed before mutation |
| `6` | verification warning/failure; requested state may already be persisted despite a secondary IBM CM error |

Scripts should always inspect the return code, especially `6`.

## Compatibility with 0.1.x

The old command forms remain accepted in 0.2.0 with a deprecation warning:

```bash
cm-retention connection test
cm-retention itemtype list
cm-retention itemtype show NAME
cm-retention itemtype assign ITEMTYPE POLICY --yes
cm-retention itemtype unassign ITEMTYPE --yes
cm-retention policy list
cm-retention policy show POLICY
cm-retention policy usage POLICY
cm-retention policy create POLICY --expiration 1y --yes
cm-retention policy delete POLICY --yes
```

New scripts should use the v0.2 top-level commands.

## Documentation

- [Detailed operations guide](DOKUMENTATION.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [IBM CM metadata repair notes](docs/METADATA_REPAIR.md)
- [Changelog](CHANGELOG.md)
