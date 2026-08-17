# CM Retention

`cm-retention` is a small, local command-line tool for administering retention and expiration policies in **IBM Content Manager Enterprise Edition 8.7**.

It uses the native IBM Content Manager Java API already installed on the CM server. No REST service, application server, Python runtime, or additional third-party library is required.

> **Status:** v0.1.2 · Java 8 · tested against IBM Content Manager 8.7
>
> This is an independent administration utility, not an IBM product. IBM SDK libraries are **not** distributed in this repository.

## What it does

Read-only operations:

```bash
bin/cm-retention connection test
bin/cm-retention itemtype list
bin/cm-retention itemtype show <ITEMTYPE>
bin/cm-retention policy list
bin/cm-retention policy show <POLICY>
bin/cm-retention policy usage <POLICY>
```

Controlled write operations:

```bash
bin/cm-retention policy create <POLICY> --expiration <AGE> [options] --yes
bin/cm-retention policy delete <POLICY> --yes
bin/cm-retention itemtype assign <ITEMTYPE> <POLICY> --yes
bin/cm-retention itemtype unassign <ITEMTYPE> --yes
```

All write commands require `--yes`. The tool deliberately has no `--force` option for deleting a policy that is still assigned.

## Safety model

`cm-retention` is intentionally narrow:

- it does **not** delete repository documents directly;
- it does **not** call `deleteExpiredItems()`;
- it does **not** backfill expiration dates on existing documents;
- it does **not** modify IBM CM system tables;
- passwords are never accepted as command-line arguments;
- `.env` must not be readable by group or other users;
- itemtype assign/unassign operations reconnect and verify the persisted state after an IBM CM error.

If IBM CM reports an error **after** the requested itemtype change was already persisted, the command exits with **code 6** instead of incorrectly reporting a clean success.

## Retention vs. expiration

The policy created by this tool is an expiration policy for automatic deletion:

```text
Type:               FIXED_TIME
Retention enabled:  false
Expiration enabled: true
Expiration action:  AUTO_DELETE
```

This distinction matters:

- **Retention** protects an item from deletion for a minimum period.
- **Expiration** determines when an item expires.
- `AUTO_DELETE` instructs IBM CM to delete expired items through its background processing.
- A policy with expiration enabled but action `NO_ACTION` does **not** automatically delete expired items.

Assigning a policy to an itemtype does not retroactively populate expiration dates for already existing documents. Existing content requires a separate, explicitly planned migration/backfill process if that is required by the business rule.

## Requirements

Typical installation paths:

```text
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Required on the target host:

- IBM Content Manager 8.7 client/server runtime
- `${IBMCMROOT}/lib/cmbicmsdk81.jar`
- IBM CM connection configuration containing the Library Server alias
- Java 8 runtime and compiler
- a CM user with the required administration permissions

The tool is intended to run locally on a CM administration/server host as a normal administration account, not as `root`.

## Installation

Clone the repository:

```bash
git clone https://github.com/mrAibo/CM_retention.git
cd CM_retention
```

Create the local configuration:

```bash
cp .env.example .env
chmod 600 .env
vi .env
```

Example:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=CHANGE_ME
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Build and test:

```bash
./build.sh
bin/cm-retention version
bin/cm-retention connection test
```

Optional installation into a dedicated directory:

```bash
./install.sh /home/ibmcmadm/cm-retention
```

## First safe test

Start read-only:

```bash
bin/cm-retention connection test
bin/cm-retention itemtype list
bin/cm-retention policy list
```

Create a policy without assigning it:

```bash
bin/cm-retention policy create ZZ_AUTO_DELETE_1Y_TEST \
  --expiration 1y \
  --yes

bin/cm-retention policy show ZZ_AUTO_DELETE_1Y_TEST
bin/cm-retention policy usage ZZ_AUTO_DELETE_1Y_TEST
```

Remove it again:

```bash
bin/cm-retention policy delete ZZ_AUTO_DELETE_1Y_TEST --yes
```

Only after that should assignment be tested, preferably with a dedicated empty test itemtype:

```bash
bin/cm-retention itemtype assign TEST_ITEMTYPE ZZ_AUTO_DELETE_1Y_TEST --yes
echo "RC=$?"

bin/cm-retention itemtype show TEST_ITEMTYPE

bin/cm-retention itemtype unassign TEST_ITEMTYPE --yes
echo "RC=$?"
```

## Create options

```text
--expiration 1y|12m|52w|365d   required
--schedule "0 2 * * *"         default: 0 2 * * *
--commit-count 100              default: 100
--max-items 5000                default: 5000; 0 = unlimited
--max-duration 120              default: 120 minutes
--force-checkin                 disabled by default
```

Example:

```bash
bin/cm-retention policy create AUTO_DELETE_1Y \
  --expiration 1y \
  --schedule "0 2 * * *" \
  --commit-count 100 \
  --max-items 5000 \
  --max-duration 120 \
  --yes
```

The schedule string is passed to IBM CM as policy schedule information. `cm-retention` does not validate its semantics; verify the intended schedule in your CM environment before production use.

## Exit codes

| Code | Meaning |
|---:|---|
| `0` | Success / requested state already present |
| `2` | Invalid arguments, missing confirmation, or configuration error |
| `3` | IBM CM API / Java / runtime error |
| `4` | Itemtype or policy not found |
| `5` | Unsafe or conflicting write operation |
| `6` | Requested itemtype change persisted, but IBM CM reported a secondary error afterward |

Always inspect the exit code of write operations:

```bash
bin/cm-retention itemtype assign TEST_ITEMTYPE POLICY_NAME --yes
rc=$?
case "$rc" in
  0) echo "OK" ;;
  6) echo "Persisted with IBM CM warning - investigate" >&2 ;;
  *) echo "Failed: RC=$rc" >&2 ;;
esac
```

## Multiple environments

Keep Test and Production configuration separate. Use independent `.env` files instead of editing one file back and forth:

```bash
cp .env.example /secure/cm-test.env
cp .env.example /secure/cm-prod.env
chmod 600 /secure/cm-test.env /secure/cm-prod.env
```

Run explicitly against one environment:

```bash
bin/cm-retention --env /secure/cm-test.env policy list
bin/cm-retention --env /secure/cm-prod.env policy list
```

or:

```bash
CM_RETENTION_ENV=/secure/cm-test.env bin/cm-retention policy list
```

## Known IBM CM metadata issue

During testing on an older CM installation, itemtype updates exposed legacy component-view metadata with:

```text
DGL0303A: Invalid parameter
DKAttrDefICM::getViewOperator() opCode : [-1]
```

The requested policy assignment could already be persisted before the secondary view-update error occurred. This is why v0.1.2 verifies the stored state after assign/unassign failures and uses exit code `6` for this case.

Do **not** repair IBM CM system tables with a blanket SQL update. See [Troubleshooting](docs/TROUBLESHOOTING.md) and [Metadata repair procedure](docs/METADATA_REPAIR.md).

## Documentation

- [Operations and user guide](DOKUMENTATION.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [IBM CM metadata repair procedure](docs/METADATA_REPAIR.md)
- [Changelog](CHANGELOG.md)

## Repository layout

```text
CM_retention/
├── .env.example
├── .gitignore
├── CHANGELOG.md
├── DOKUMENTATION.md
├── README.md
├── build.sh
├── install.sh
├── bin/
│   └── cm-retention
├── docs/
│   ├── METADATA_REPAIR.md
│   └── TROUBLESHOOTING.md
└── src/
    └── CmRetention.java
```

Generated artifacts and local secrets are intentionally excluded from Git:

```text
.env
build/
*.class
*.jar
```
