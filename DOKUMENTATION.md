# cm-retention 0.2.0 – Betriebs- und Benutzerdokumentation

## 1. Zweck

`cm-retention` verwaltet Retention-/Expiration-Policies in IBM Content Manager Enterprise Edition 8.7 über die offizielle Java-API.

Das Werkzeug ist für Administratoren gedacht. Es soll kurz, vorhersehbar und scriptbar bleiben, bietet aber bei einem echten Terminal interaktive Auswahl und Bestätigung.

Bewusste Grenzen:

- keine direkte Dokumentlöschung
- kein manueller Aufruf von `deleteExpiredItems()`
- kein automatisches Backfill bestehender Dokumente
- keine Schreibzugriffe auf IBM-CM-Systemtabellen
- kein GUI-/TUI-Framework
- keine zusätzliche Runtime-Dependency neben Java 8 und der vorhandenen IBM-CM-Installation

## 2. Sicherheitsprinzip

Jede schreibende Operation folgt demselben Ablauf:

```text
1. Ziel auflösen
2. aktuellen Zustand lesen
3. Eingaben und Voraussetzungen prüfen
4. Änderungsplan anzeigen
5. Dry-run / Bestätigung behandeln
6. IBM-CM-Änderung ausführen
7. commit
8. Zustand erneut lesen / verifizieren
9. Ergebnis und Exit-Code melden
```

Für Assign/Unassign bleibt die besondere Absicherung aus 0.1.2 erhalten: Meldet IBM CM während eines ItemType-Updates einen Fehler, wird die Verbindung geschlossen, neu aufgebaut und der tatsächlich persistierte Policy-Zustand erneut gelesen.

Wenn die gewünschte Änderung bereits gespeichert wurde, IBM CM danach aber noch einen sekundären Fehler meldet, endet das Tool bewusst mit **Exit-Code 6** und nicht mit einem falschen sauberen Erfolg.

Zusätzlich schützt 0.2.0 vor veralteten Plänen: Direkt vor Assign/Unassign wird die aktuelle Zuordnung erneut gelesen. Hat sie sich zwischen Plananzeige und Mutation verändert, wird die Operation mit Exit `5` abgebrochen. Delete prüft die Policy-Nutzung unmittelbar vor dem Löschen erneut.

## 3. Installation

### 3.1 Voraussetzungen

Typische Installation:

```text
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
IBM CM SDK=/opt/IBM/db2cmv8/lib/cmbicmsdk81.jar
```

### 3.2 Interaktiver Installer

```bash
./install.sh
```

Der Installer fragt ab:

```text
IBM CM root
Java home
CM database
CM user
CM password
```

Das Passwort wird mit versteckter Terminaleingabe gelesen. Anschließend:

- `.env` wird erzeugt
- Dateimodus wird auf `0600` gesetzt
- `build.sh` wird ausgeführt
- `cm-retention status` prüft Verbindung und APIs

Wenn `install.sh` ohne TTY ausgeführt wird, bleibt der automatisierbare Ablauf erhalten: `.env.example` wird als `.env` angelegt, das Projekt gebaut und die noch notwendige Konfiguration angezeigt.

## 4. Konfiguration

```bash
cp .env.example .env
chmod 600 .env
```

Beispiel:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=change-me
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Die `.env` wird direkt geparst und **nicht** als Shell-Datei ausgeführt.

Passwörter gehören nicht auf die Kommandozeile.

## 5. TEST und PROD strikt trennen

Für unterschiedliche Content-Manager-Systeme separate Konfigurationsdateien verwenden:

```text
.env.test
.env.prod
```

```bash
chmod 600 .env.test .env.prod
```

Aufruf:

```bash
bin/cm-retention --env .env.test status
bin/cm-retention --env .env.prod status
```

Damit ist immer sichtbar, gegen welchen dedizierten Server/Alias gearbeitet wird. Nicht eine `.env` ständig zwischen TEST und PROD umschreiben.

## 6. Build

```bash
./build.sh
```

Der Build kompiliert alle Dateien unter `src/` mit Java-8-Source/Target und den lokal installierten IBM-CM-JARs.

Ergebnis:

```text
build/cm-retention.jar
```

Manifest-Version:

```text
Implementation-Version: 0.2.0
```

## 7. Architektur

Die v0.2-Quellen sind bewusst klein getrennt:

```text
src/CmRetention.java   Entry Point, Exit-Codes, zentrale Fehlerausgabe
src/CmCli.java         Commands, TTY-Interaktion, Pläne, Bestätigungen
src/CmService.java     IBM-CM-API, Mutationen, Reconnect/Verifikation
src/Config.java        .env-Konfiguration
src/CliArgs.java       Parser, PolicySettings, Age, CLI-Exceptions
```

Es gibt weiterhin keine externe CLI-Library.

## 8. Neue CLI 0.2

```text
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

### Interaktiver Root-Modus

```bash
bin/cm-retention
```

Bei einem TTY erscheint:

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

Es gibt keine Vollbild-GUI. Alle Menüpunkte rufen denselben Command-Core auf wie die direkte CLI.

## 9. Read-only-Befehle

### Status

```bash
bin/cm-retention status
```

Beispiel:

```text
CM Retention 0.2.0

Configuration
  File       : /home/ibmcmadm/cm-retention/.env
  Database   : LSDB
  User       : icmadmin

Runtime
  Java       : 1.8.0_xxx
  IBM CM API : 8.7.0.000

Content Manager
  Connection : OK
  Datastore  : LSDB
  Policies   : 7
  Item types : 43

Status       : OK
```

### Policies

```bash
bin/cm-retention policies
bin/cm-retention policy RET_5Y
```

`policy RET_5Y` zeigt auch die verwendenden ItemTypes. Ein separates `usage` ist in der neuen CLI daher nicht mehr notwendig.

### ItemTypes

```bash
bin/cm-retention itemtypes
bin/cm-retention itemtype INVOICE
```

Unter anderem werden Retention-Policy, Versionierung und Auto-Delete-Einstellungen angezeigt.

## 10. Create

Normalfall:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y
```

Das Tool erzeugt bewusst eine feste Policy-Struktur:

```text
Retention type     FIXED_TIME
Retention enabled  false
Expiration enabled true
Expiration action  AUTO_DELETE
```

Standardwerte:

```text
schedule       0 2 * * *
commit-count   100
max-items      5000
max-duration   120
force-checkin  false
```

Vor der Änderung erscheint ein Plan:

```text
Create AUTO_DELETE policy

Name         : AUTO_DELETE_5Y
Expiration   : 5 years
Action       : AUTO_DELETE
Schedule     : daily 02:00 (0 2 * * *)
Limits       : 5000 items / 120 min
Commit       : every 100 items
Force checkin: no

Create? [y/N]:
```

Nur `y`/`yes` führt die Änderung aus. Enter bedeutet Nein.

### Advanced Overrides

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y \
  --schedule "0 4 * * *" \
  --commit-count 200 \
  --max-items 10000 \
  --max-duration 180
```

Optional:

```text
--force-checkin
```

Alle Optionen:

```bash
bin/cm-retention create --help
```

## 11. Assign

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y
```

Vor der Änderung:

```text
Assign retention policy

Item type : INVOICE
Current   : RET_1Y
New       : AUTO_DELETE_5Y
Expiration: 5 YEAR
Action    : AUTO_DELETE

Existing items are not backfilled.

Apply? [y/N]:
```

Ist die gewünschte Policy bereits gesetzt:

```text
No change: INVOICE already uses AUTO_DELETE_5Y.
```

Exit-Code `0`.

## 12. Unassign

```bash
bin/cm-retention unassign INVOICE
```

Ist keine Policy vorhanden:

```text
No change: INVOICE has no retention policy.
```

Exit-Code `0`.

## 13. Delete

```bash
bin/cm-retention delete AUTO_DELETE_5Y
```

Eine Policy kann nicht gelöscht werden, solange ItemTypes sie verwenden.

Delete ist ebenfalls explizit konservativ:

```text
Delete permanently? [y/N]:
```

## 14. Dry-run

Für jede schreibende Top-Level-Operation:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y --dry-run
bin/cm-retention unassign INVOICE --dry-run
bin/cm-retention create AUTO_DELETE_5Y 5y --dry-run
bin/cm-retention delete AUTO_DELETE_5Y --dry-run
```

Dry-run bedeutet:

- Ziele werden aufgelöst
- Existenz/Usage/Optionen werden validiert
- aktueller Zustand wird gelesen
- kompletter Plan wird ausgegeben
- keine schreibende IBM-CM-Operation wird ausgelöst
- kein `--yes` erforderlich
- erfolgreicher Dry-run endet mit `0`

## 15. Interaktive Argumentauflösung

Fehlt in einem TTY ein Argument:

```bash
bin/cm-retention assign
```

werden ItemType und Policy angeboten.

Eingabe kann sein:

- Nummer
- exakter Name
- eindeutiger Präfix

Beispiel:

```text
Item type: inv
Resolved: inv -> INVOICE
```

Bei mehreren Treffern wird nicht geraten, sondern erneut gefragt.

Wichtig: Diese Präfixauflösung gilt **nur interaktiv**.

In Scripts:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y --yes
```

müssen ItemType und Policy exakt angegeben werden.

## 16. Non-TTY / Automation

Regeln:

```text
vollständige Argumente + --yes   -> Ausführung
vollständige Argumente + dry-run -> Vorschau
fehlendes Argument               -> Exit 2
kein --yes bei echter Write-Operation -> Exit 2
```

Damit kann kein Cronjob auf einem Prompt hängen bleiben.

## 17. Strikter Parser

Unbekannte Optionen werden abgelehnt:

```bash
bin/cm-retention create RET_5Y 5y --max-item 5000
```

Ergebnis:

```text
ERROR: Unknown option '--max-item'
```

Auch doppelte Optionen/Flags werden abgelehnt.

Das ist eine Safety-Funktion gegen Operator-Tippfehler.

## 18. Doctor

```bash
bin/cm-retention doctor
```

Der Launcher prüft zuerst lokal:

```text
configuration file
configuration mode
Java executable
IBM CM SDK
native library path
CM configuration path
application JAR
```

Danach prüft Java:

```text
configuration readable
Java runtime
IBM CM API loaded
CM login
policy API
itemtype API
```

`doctor` ersetzt nicht die IBM-CM-Logs, ist aber der erste Diagnosebefehl für Installation/Verbindung.

## 19. Legacy-Kompatibilität

0.1.x-Befehle bleiben vorerst erhalten:

```text
connection test
itemtype list
itemtype show
itemtype assign
itemtype unassign
policy list
policy show
policy usage
policy create
policy delete
```

Beispiel:

```bash
bin/cm-retention policy create RET_1Y --expiration 1y --yes
```

wird weiterhin ausgeführt, aber mit Hinweis auf:

```bash
bin/cm-retention create RET_1Y 1y --yes
```

Damit brechen vorhandene Scripts nicht durch das 0.2-Upgrade.

## 20. Exit-Codes

| Code | Bedeutung |
|---:|---|
| `0` | Erfolg, idempotenter No-op oder erfolgreicher Dry-run |
| `2` | CLI-/Konfigurations-/Preflight-/Bestätigungsfehler |
| `3` | IBM-CM-/Runtime-Fehler |
| `4` | ItemType oder Policy nicht gefunden |
| `5` | Konflikt/unsichere Operation; z. B. Policy existiert bereits, wird verwendet oder der angezeigte Zustand wurde zwischenzeitlich geändert |
| `6` | Verifikationswarnung/-fehler; gewünschter Zustand kann trotz sekundärem IBM-Fehler bereits persistiert sein |

### Exit 6

Gerade bei älteren ItemType-Metadaten kann IBM CM einen Update-Schritt teilweise persistieren und danach beim erneuten Verarbeiten weiterer Metadaten eine Exception werfen.

Assign/Unassign behandelt das so:

```text
IBM API error
 -> Verbindung schließen
 -> neu verbinden
 -> ItemType erneut lesen
 -> gewünschten Zustand vergleichen
```

Ist der gewünschte Zustand bereits gespeichert, meldet das Tool Warning + Exit `6`.

Das ist absichtlich weder `0` noch ein gewöhnlicher Fehler `3`.

## 21. Retention vs. Expiration

`cm-retention create NAME 5y` erstellt eine **Expiration-/Auto-Delete-Policy** mit deaktivierter Retention-Sperrfrist und aktiviertem Expiration-Zeitpunkt.

Das ist nicht gleichbedeutend mit einer klassischen Mindestaufbewahrungsfrist.

Außerdem gilt:

> Das spätere Zuweisen einer Policy zu einem ItemType setzt nicht automatisch rückwirkend Expiration-Daten für bereits existierende Dokumente.

Ein Backfill wäre ein eigener, deutlich riskanterer Prozess und ist bewusst nicht Bestandteil dieses Tools.

## 22. Empfohlener Produktionsworkflow

Vor einer echten Änderung:

```bash
bin/cm-retention --env .env.prod status
bin/cm-retention --env .env.prod policy RET_5Y
bin/cm-retention --env .env.prod itemtype INVOICE
bin/cm-retention --env .env.prod assign INVOICE RET_5Y --dry-run
```

Erst danach:

```bash
bin/cm-retention --env .env.prod assign INVOICE RET_5Y
```

oder für kontrollierte Automation:

```bash
bin/cm-retention --env .env.prod assign INVOICE RET_5Y --yes
```

Anschließend:

```bash
bin/cm-retention --env .env.prod itemtype INVOICE
```

## 23. Troubleshooting

Siehe:

- `docs/TROUBLESHOOTING.md`
- `docs/METADATA_REPAIR.md`

Die dort dokumentierten IBM-CM-Metadatenprobleme sind **kein Bestandteil der automatischen Tool-Reparatur**. `cm-retention` schreibt niemals direkt in interne CM-Systemtabellen.
