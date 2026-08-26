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

---

# Installation

This section describes a complete installation on an IBM Content Manager server, from cloning the repository to the first successful connection test.

## 1. Prerequisites

The tool is intended to run locally on a server where IBM Content Manager 8.7 is already installed.

Typical paths used by the project:

```text
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Required files/directories include:

```text
${IBMCMROOT}/lib/cmbicmsdk81.jar
${IBMCMROOT}/lib/
${IBMCMROOT}/cmgmt/
${JAVA_HOME}/bin/java
${JAVA_HOME}/bin/javac
${JAVA_HOME}/bin/jar
```

The configured IBM CM library-server alias, for example `LSDB`, must already exist in the local IBM CM configuration.

Recommended runtime user:

```text
ibmcmadm
```

Do not install or operate the tool as `root` unless this is explicitly required by your local administration model.

Before installation, verify the main prerequisites:

```bash
ls -l /opt/IBM/db2cmv8/lib/cmbicmsdk81.jar
/opt/IBM/WebSphere/AppServer/java/8.0/bin/java -version
/opt/IBM/WebSphere/AppServer/java/8.0/bin/javac -version
```

## 2. Clone the repository

As the intended runtime user:

```bash
cd /home/ibmcmadm

git clone https://github.com/mrAibo/CM_retention.git
cd CM_retention
```

Check the repository state:

```bash
git status
git log -1 --oneline
```

For v0.2.x the source must report:

```bash
grep 'VERSION =' src/CmRetention.java
```

Expected:

```text
static final String VERSION = "0.2.0";
```

## 3. Choose the configuration model

For a single environment, create `.env`:

```bash
cp .env.example .env
chmod 600 .env
```

Edit it:

```bash
vi .env
```

Example:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=change-me
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Important:

- `.env` is ignored by Git and must never be committed.
- the password is not passed as a CLI argument.
- the launcher refuses configuration files that are readable by group or other users.
- use mode `0600` or stricter.

Verify:

```bash
ls -l .env
stat -c '%a %n' .env
```

Expected mode:

```text
600 .env
```

## 4. Recommended: separate TEST and PROD completely

TEST and PROD should be treated as dedicated server configurations, not as one configuration file that is edited back and forth.

Create separate files:

```bash
cp .env.example .env.test
cp .env.example .env.prod
chmod 600 .env.test .env.prod
```

Example TEST configuration:

```dotenv
CM_DATABASE=LSDB_TEST
CM_USER=icmadmin
CM_PASSWORD=<test-password>
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Example PROD configuration:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=<prod-password>
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Use them explicitly:

```bash
bin/cm-retention --env .env.test status
bin/cm-retention --env .env.prod status
```

A write can then be targeted clearly:

```bash
bin/cm-retention --env .env.test assign AM AUTO_DELETE_1Y --dry-run
```

For production, always verify the target first:

```bash
bin/cm-retention --env .env.prod status
```

before executing a write command.

## 5. Build the tool

From the repository root:

```bash
./build.sh
```

The build uses the locally installed IBM CM SDK and Java 8.

It compiles all Java sources under:

```text
src/
```

and creates:

```text
build/cm-retention.jar
build/.version
```

The JAR manifest contains:

```text
Main-Class: CmRetention
Implementation-Version: 0.2.0
```

Check the build output:

```bash
ls -l build/cm-retention.jar build/.version
cat build/.version
```

Expected version:

```text
0.2.0
```

## 6. Verify the installed version

Run:

```bash
bin/cm-retention version
```

Expected:

```text
cm-retention 0.2.0
```

If you see an older version such as `0.1.2`, do not continue with administrative operations. Rebuild the tool as described in the update/troubleshooting section below.

## 7. Run the first connection test

The recommended first command is:

```bash
bin/cm-retention status
```

Typical output:

```text
CM Retention 0.2.0

Configuration
  File       : /home/ibmcmadm/CM_retention/.env
  Database   : LSDB
  User       : icmadmin

Runtime
  Java       : 1.8.0_xxx
  IBM CM API : 8.7.x

Content Manager
  Connection : OK
  Datastore  : LSDB
  Policies   : ...
  Item types : ...

Status       : OK
```

Then run the deeper diagnostics:

```bash
bin/cm-retention doctor
```

`doctor` checks the launcher/runtime first and then the IBM CM connection/API.

Among other things it validates:

```text
configuration file
configuration permissions
Java executable
IBM CM SDK
IBM CM native library path
IBM CM configuration directory
application JAR
build version/freshness
IBM CM login
policy API
item type API
```

## 8. Verify read-only commands before any write

Run at least:

```bash
bin/cm-retention policies
bin/cm-retention itemtypes
```

Inspect one real policy or item type:

```bash
bin/cm-retention policy AUTO_DELETE_1Y
bin/cm-retention itemtype AM
```

Only after these commands return the expected environment/data should write operations be tested.

## 9. First safe write test

Use `--dry-run` first:

```bash
bin/cm-retention create ZZ_CM_RETENTION_TEST 1d --dry-run
```

or for an assignment:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --dry-run
```

A dry-run performs validation and prints the intended plan but does not mutate IBM CM.

For a real write in an interactive terminal:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y
```

The tool prints the plan and asks:

```text
Apply? [y/N]:
```

Only explicit `y` executes the change.

For non-interactive automation, use `--yes`:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --yes
```

## 10. Interactive installer alternative

Instead of manually creating `.env` and building, a first installation can use:

```bash
./install.sh
```

The installer prompts for:

```text
IBM CM root
Java home
CM database
CM user
CM password
```

The password is read with hidden terminal input.

The installer then:

1. writes the configuration,
2. applies mode `0600`,
3. builds the JAR,
4. runs the status/connection check.

For environments with dedicated TEST/PROD files, manual configuration is usually clearer because the target file can be named explicitly.

## 11. Updating an existing installation

From the Git checkout:

```bash
cd /home/ibmcmadm/CM_retention

git status
git pull --ff-only
./build.sh
bin/cm-retention version
bin/cm-retention status
```

Do **not** assume that `git pull` automatically rebuilds the Java JAR.

The source files and the compiled JAR are separate. After a source update, always run:

```bash
./build.sh
```

The v0.2 launcher now detects stale builds and refuses to run them.

Typical messages are:

```text
ERROR: build artifact predates v0.2.0 or is stale; run: .../build.sh
```

or:

```text
ERROR: source is newer than the application jar; run: .../build.sh
```

If you have upgraded from 0.1.x and want a completely clean rebuild:

```bash
git pull --ff-only
rm -rf build
./build.sh
bin/cm-retention version
```

Expected:

```text
cm-retention 0.2.0
```

## 12. Detecting duplicate old installations

If commands still behave like v0.1.x after updating, check whether multiple copies exist:

```bash
find /home/ibmcmadm -maxdepth 2 -type d \
  \( -name 'cm-retention' -o -name 'CM_retention' \) \
  -print
```

For each result, check:

```bash
cd /path/to/repository
pwd
bin/cm-retention version
```

A typical cause of confusion is having both:

```text
/home/ibmcmadm/cm-retention
/home/ibmcmadm/CM_retention
```

where one directory contains the old 0.1.x installation and the other contains the current Git checkout.

Also check which command is actually executed if a global command or symlink exists:

```bash
command -v cm-retention
readlink -f "$(command -v cm-retention)" 2>/dev/null || true
```

## 13. Installation troubleshooting

### `ERROR: configuration file not found`

Create the file and secure it:

```bash
cp .env.example .env
chmod 600 .env
vi .env
```

### `ERROR: insecure permissions`

Fix permissions:

```bash
chmod 600 .env
```

or for dedicated environments:

```bash
chmod 600 .env.test .env.prod
```

### `ERROR: Java runtime not executable`

Check `JAVA_HOME` in the configuration:

```bash
ls -l /opt/IBM/WebSphere/AppServer/java/8.0/bin/java
```

### `ERROR: IBM CM SDK not found`

Check `IBMCMROOT` and the SDK:

```bash
ls -l /opt/IBM/db2cmv8/lib/cmbicmsdk81.jar
```

### `ERROR: tool is not built`

Run:

```bash
./build.sh
```

### Old v0.1.x syntax appears after `git pull`

Check:

```bash
pwd
git rev-parse --short HEAD
grep 'VERSION =' src/CmRetention.java
bin/cm-retention version
```

Then perform a clean rebuild:

```bash
rm -rf build
./build.sh
bin/cm-retention version
```

If the version remains old, verify that you are in the correct checkout and not in a second legacy installation.

---

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
