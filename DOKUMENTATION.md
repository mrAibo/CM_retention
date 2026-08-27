# cm-retention 0.4.2 – Betriebs- und Benutzerdokumentation

## 1. Zweck

`cm-retention` verwaltet Retention-/Expiration-Policies in IBM Content Manager Enterprise Edition 8.7. Das Werkzeug bleibt bewusst klein: Java 8, vorhandenes IBM-CM-SDK, Bash-Launcher, keine GUI und kein zusätzliches CLI-Framework.

Unterstützt werden:

- Policy- und ItemType-Anzeige einschließlich Policy-Verwendung
- Erstellen von FIXED_TIME/AUTO_DELETE-Policies
- Policy-Erstellung aus `.properties`-Vorlagen
- Assign/Unassign und Dry-run
- Batch-Verarbeitung mit `--file` in einem JVM-Prozess
- Existing-Item-Backfill mit `--backfill`
- direkter Backfill auf DB2 und Oracle 19c
- Policy-/Root-Fingerprints und Post-COMMIT-Schutz
- verifizierte IBM-CM-Sekundärwarnungen im Batch ohne unnötigen Abbruch
- unabhängige Batch-Endverifikation in einer zweiten JVM/frischen CM-Session
- automatische Batch-Audit-Logs und Retry-Dateien für bestätigte Mismatches
- Laufzeitmessungen
- reiner Regressionstest mit `selftest`

Das Tool löscht Dokumente nicht direkt und ruft `deleteExpiredItems()` nicht selbst auf.

## 2. Datenbank-Unterstützung

Die normalen Verwaltungsoperationen laufen über das IBM-CM-SDK und funktionieren sowohl gegen DB2- als auch Oracle-basierte Library Server:

```text
status / doctor
policies / policy
itemtypes / itemtype
create / delete
assign / unassign
--file ohne --backfill
```

Der direkte `--backfill` unterstützt seit 0.4.0:

| Library-Server-DB | Normale CM-Befehle | `--backfill` |
|---|---:|---:|
| DB2 | ja | ja |
| Oracle 19c | ja | ja |

### 2.1 Wichtiger Unterschied beim AUTO_DELETE-Schedule

IBM Content Manager erwartet abhängig von der Library-Server-Datenbank unterschiedliche Schedule-Syntax:

```text
DB2     -> UNIX cron
Oracle  -> Oracle calendaring syntax
```

Beispiele für täglich 02:00:

DB2:

```text
0 2 * * *
```

Oracle:

```text
FREQ=DAILY;BYHOUR=2;BYMINUTE=0;BYSECOND=0;
```

Deshalb werden getrennte fertige Profile mitgeliefert:

```text
profiles/auto-delete-1y.properties
profiles/auto-delete-5y.properties
profiles/auto-delete-10y.properties

profiles/auto-delete-1y-oracle.properties
profiles/auto-delete-5y-oracle.properties
profiles/auto-delete-10y-oracle.properties
```

Die Policy-Semantik ist gleich; nur der Schedule-String unterscheidet sich. Die nicht mit `-oracle` gekennzeichneten Vorlagen und `ret-policy.properties` bleiben aus Kompatibilitätsgründen DB2-orientiert.

## 3. Version und Self-Test

```bash
bin/cm-retention version
bin/cm-retention selftest
```

Erwartet:

```text
cm-retention 0.4.2
Self-test: OK (... checks)
```

Der Self-Test meldet sich nicht an Content Manager an und öffnet keine direkte DB2-/Oracle-Verbindung. Geprüft werden unter anderem Parser, Template-Erkennung, Fingerprints, `CREATETS`, DB2-/Oracle-SQL-Dialekte, Warning-Summary, Final-Verifier-Policyvergleich und Sekunden-Einheiten.

## 4. Policy-Vorlagen

Vollständiges unterstütztes Modell:

```properties
RET_POLICY_NAME=AUTO_DELETE_1Y

retention.type=FIXED_TIME
retention.enabled=false

expiration.enabled=true
expiration.age=1y
expiration.action=AUTO_DELETE

auto-delete.schedule=0 2 * * *
auto-delete.commit-count=100
auto-delete.max-items=5000
auto-delete.max-duration=120
auto-delete.force-checkin=true
```

`auto-delete.max-duration` wird in **Sekunden** angegeben. `120` = 120 Sekunden = 2 Minuten.

`auto-delete.force-checkin=true` bedeutet **Einchecken vor Löschen erzwingen**.

### 4.1 DB2-Policy erstellen

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --dry-run
bin/cm-retention create profiles/auto-delete-5y.properties
```

### 4.2 Oracle-Policy erstellen

```bash
bin/cm-retention create profiles/auto-delete-5y-oracle.properties --dry-run
bin/cm-retention create profiles/auto-delete-5y-oracle.properties
```

Oracle-Vorlage:

```properties
auto-delete.schedule=FREQ=DAILY;BYHOUR=2;BYMINUTE=0;BYSECOND=0;
```

Ein `--schedule`-CLI-Override gewinnt weiterhin gegen die Properties. Der Administrator muss dabei die Syntax der Ziel-Datenbank verwenden.

### 4.3 Template-Erkennung und Priorität

Eine lesbare `.properties`-Datei wird automatisch erkannt, wenn sie das einzige Positionsargument von `create` ist:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --dry-run
```

Explizit bleibt möglich:

```bash
bin/cm-retention create --properties profiles/auto-delete-5y.properties
```

Priorität:

```text
CLI POLICY / AGE / Optionen
  > ausgewählte Properties-Vorlage
  > ret-policy.properties
  > eingebaute Defaults
```

## 5. Policies und ItemTypes anzeigen

```bash
bin/cm-retention policies
bin/cm-retention policy AUTO_DELETE_5Y
bin/cm-retention itemtypes
bin/cm-retention itemtype AM
```

`policies` zeigt pro Policy die Anzahl der zugewiesenen ItemTypes. `policy POLICY` zeigt zusätzlich die konkreten ItemTypes:

```text
Assigned itemtypes:         3
  - AM
  - CONTRACT
  - INVOICE
```

Bei keiner Verwendung:

```text
Assigned itemtypes:         0
```

Die maximale AUTO_DELETE-Laufzeit wird eindeutig in Sekunden ausgegeben:

```text
Auto-delete max. duration:  120 sec
```

## 6. Normaler Assign/Unassign

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --dry-run
bin/cm-retention assign AM AUTO_DELETE_1Y
bin/cm-retention unassign AM --dry-run
bin/cm-retention unassign AM
```

Ohne `--backfill` werden bestehende Dokument-Metadaten nicht verändert. Writes behalten stale-state- und reconnect/persisted-state-Verifikation.

### 6.1 IBM-CM-Sekundärfehler nach bereits persistiertem Write

Bei älteren ItemTypes kann IBM CM nach einem erfolgreichen Assign/Unassign noch einen SDK-Fehler melden, beispielsweise:

```text
DGL0303A: Ungültiger Parameter.
DKAttrDefICM::getViewOperator() opCode : [-1]
```

`CmService` behandelt dies nicht automatisch als Erfolg. Nach dem SDK-Fehler wird eine neue CM-Session geöffnet und der persistierte Policy-Zustand erneut gelesen.

Nur wenn der angeforderte Zustand tatsächlich bestätigt wurde, entsteht eine `OperationWarning`:

```text
Policy AUTO_DELETE_1Y was removed from itemtype AM,
but IBM CM reported a secondary error after persisting the change.
```

Ein Einzelbefehl liefert dafür weiterhin Exit `6`.

Ab 0.4.1 gilt für `--file`:

```text
OperationWarning + gewünschter Zustand verifiziert
  -> Warning protokollieren
  -> ItemType als verified zählen
  -> Batch fortsetzen
  -> Gesamt-RC am Ende 6

Zustand nicht verifizierbar / anderer Fehler
  -> Batch sofort stoppen
```

Damit wird ein Legacy-Metadatenproblem nicht verschluckt, aber ein großer Batch muss nach einem bereits erfolgreich persistierten Sekundärfehler nicht unnötig abgebrochen werden.

### 6.2 Bereits zugewiesene AUTO_DELETE-Policy ändern

Bei einer bereits vielen ItemTypes zugewiesenen AUTO_DELETE-Policy sollte eine nachträgliche Änderung von Schedule oder anderen Auto-Delete-Einstellungen kontrolliert erfolgen. In realen CM-8.7-Umgebungen können die per-ItemType Automatic-Delete-Tasks/Schedules sonst nicht wie erwartet neu aufgebaut werden.

Besonders relevant sind:

```text
expiration.age
auto-delete.schedule
auto-delete.commit-count
auto-delete.max-items
auto-delete.max-duration
auto-delete.force-checkin
```

Empfohlener Ablauf:

```text
1. betroffene ItemTypes prüfen
2. Policy unassignen
3. Policy ändern oder neue Policy erstellen
4. Policy erneut assignen
5. Automatic-Delete-Tasks/Schedule prüfen
6. --backfill nur verwenden, wenn Existing-Item-Daten tatsächlich gesetzt/repariert werden müssen
```

Eine Neuzuweisung berechnet bereits vorhandene `ICM$AUTODELETEDATE`-Werte nicht automatisch neu.

## 7. Existing-Item-Backfill

Immer zuerst:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

Der Single-Item-Dry-run zeigt die ausgewählte direkte Datenbank vor dem Detailplan explizit an:

```text
Backfill database           : DB2
```

Die logische Operation lautet:

```sql
UPDATE <SCHEMA>.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + <Policy-Frist>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

Wichtig: die physische Spalte heißt `CREATETS`, nicht `ICM$CREATETS`.

### 7.1 DB2-Dialekt

Beispiel ein Jahr:

```text
ICM$AUTODELETEDATE = CREATETS + 1 YEAR
```

Für die Plan-Berechnung wird `CURRENT TIMESTAMP` verwendet. Der fail-fast Probe nutzt `FETCH FIRST 1 ROW ONLY`.

### 7.2 Oracle-Dialekt

Der Oracle-Dialekt verwendet Interval-Literale. Beispiel ein Jahr:

```text
ICM$AUTODELETEDATE = CREATETS + INTERVAL '1' YEAR
```

Generierung:

```text
YEAR  -> INTERVAL 'N' YEAR
MONTH -> INTERVAL 'N' MONTH
WEEK  -> INTERVAL 'N*7' DAY
DAY   -> INTERVAL 'N' DAY
```

Bei mehr als zwei führenden Stellen wird die erforderliche Oracle-Precision explizit ergänzt:

```text
300 Monate -> INTERVAL '300' MONTH(3)
52 Wochen  -> INTERVAL '364' DAY(3)
365 Tage   -> INTERVAL '365' DAY(3)
```

Oracle-Intervalle mit mehr als neun führenden Stellen werden fail-closed abgelehnt.

Für die Plan-Berechnung wird `CURRENT_TIMESTAMP` verwendet. Der fail-fast Probe nutzt `ROWNUM = 1`.

### 7.3 Unterstützte Policy-Semantik

`--backfill` akzeptiert nur:

```text
Retention type     FIXED_TIME
Retention enabled  false
Expiration enabled true
Expiration action  AUTO_DELETE
Expiration period  > 0
Unit               YEAR / MONTH / WEEK / DAY
```

### 7.4 Detailed plan

Phase 1/Dry-run berechnet die Plan-Zähler in einem aggregierten SELECT pro Root-Tabelle:

```text
Root rows total
Missing both dates
Backfillable rows
NULL create timestamp
Immediately expired after
Already auto-delete dated
Retention date already set
```

`Immediately expired after` ist sicherheitskritisch: diese Objekte erhalten ein Auto-Delete-Datum in der Vergangenheit und können nach Policy-Zuweisung unmittelbar für AUTO_DELETE eligible werden.

### 7.5 Phase-2 Fast Path

Vor dem Write wird der vollständige Statistik-Scan nicht wiederholt. Stattdessen werden frisch geprüft:

- ItemType-Zuweisung
- vollständiger Policy-Fingerprint
- ItemTypeID / ComponentTypeID / SegmentID / ICMUT-Tabelle
- Existenz eines problematischen eligible Rows mit `NULL CREATETS`

### 7.6 Policy- und Root-Fingerprint

Policy-Fingerprint:

```text
Name
Retention type/enabled/period/unit
Expiration enabled/period/unit/action
Auto-delete schedule
Commit count
Max items
Max duration
Force check-in
```

Root-Fingerprint:

```text
ItemTypeID
ComponentTypeID
SegmentID
ICMUT table
```

Prüfpunkte:

1. vor dem direkten DB-UPDATE – Abweichung => Exit `5`, kein Backfill-Write;
2. nach DB-COMMIT und vor Policy-Assign – Abweichung nach geschriebenen Rows => Exit `6`, Policy wird nicht zugewiesen;
3. bei finaler Verifikation – Abweichung => Exit `6`.

### 7.7 Transaktionsgrenze

```text
detailed plan + fingerprints
 -> confirmation/dry-run
 -> fresh ItemType/Policy/Root guard
 -> cheap NULL-CREATETS probe
 -> direct database UPDATE
 -> database COMMIT
 -> residual-NULL verification
 -> fresh post-COMMIT guard
 -> IBM CM policy assignment
 -> CM reconnect/persisted-state verification
 -> final Policy/Root/assignment/database verification
```

Direkter DB-Backfill und IBM-CM-Assignment sind keine verteilte gemeinsame Transaktion. Ein Fehler nach DB-COMMIT kann deshalb einen Partial-Success-Zustand erzeugen. Dieser wird mit Exit `6` sichtbar gemacht.

Eine `OperationWarning` aus dem Assignment wird im Backfill erst dann als **verified warning** an den Batch weitergegeben, wenn anschließend auch Policy-Fingerprint, Root-Fingerprint, Assignment und residual NULL rows vollständig verifiziert wurden. Andere RC6-Zustände bleiben fail-closed.

## 8. Backfill-Konfiguration

Normale Non-Backfill-Befehle benötigen diese Werte nicht. Ein fehlerhaft konfigurierter direct-JDBC-Pfad blockiert `--backfill` bzw. wird durch `doctor` angezeigt, aber blockiert keine normalen CM-SDK-Kommandos.

### 8.1 Bevorzugte neutrale DB2-Konfiguration

```dotenv
BACKFILL_DB_TYPE=db2
BACKFILL_JDBC_URL=jdbc:db2:LSDB
BACKFILL_USER=icmadmin
BACKFILL_PASSWORD=CHANGE_ME
BACKFILL_SCHEMA=ICMADMIN
BACKFILL_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

Netzwerk-URL:

```dotenv
BACKFILL_JDBC_URL=jdbc:db2://dbhost.example:50000/LSDB
```

Alte `DB2_*`-Namen bleiben kompatibel. Wenn überhaupt keine Backfill-DB-Konfiguration vorhanden ist, bleibt das historische Default-Verhalten:

```text
jdbc:db2:<CM_DATABASE>
```

### 8.2 Oracle 19c

```dotenv
ORACLE_HOME=/u01/app/oracle/product/19.0.0/dbhome_1
BACKFILL_DB_TYPE=oracle
BACKFILL_JDBC_URL=jdbc:oracle:thin:@//dbhost.example:1521/LSDB
BACKFILL_USER=icmconct
BACKFILL_PASSWORD=CHANGE_ME
BACKFILL_SCHEMA=ICMADMIN
BACKFILL_JDBC_JAR=/u01/app/oracle/product/19.0.0/dbhome_1/jdbc/lib/ojdbc8.jar
```

Für Oracle ist ein expliziter JDBC-URL erforderlich. Listener/Service werden **nicht** aus `CM_DATABASE` geraten.

`ORACLE_HOME` wird auch aus `.env` gelesen. Der Launcher sucht `ojdbc8.jar` unter anderem unter `$ORACLE_HOME/jdbc/lib` und in bekannten IBM/WAS-Pfaden. Ein explizites `BACKFILL_JDBC_JAR` ist für Produktion am eindeutigsten.

Auch folgende Oracle-Aliase werden akzeptiert:

```text
ORACLE_JDBC_URL
ORACLE_USER
ORACLE_PASSWORD
ORACLE_SCHEMA
ORACLE_JDBC_JAR
```

Bevorzugt werden die neutralen `BACKFILL_*`-Namen.

### 8.3 Auto-Detection

```dotenv
BACKFILL_DB_TYPE=auto
```

oder ein fehlender Typ erkennt die Datenbank am URL-Präfix:

```text
jdbc:db2:...     -> DB2
jdbc:oracle:...  -> Oracle
```

Ein Widerspruch zwischen fest gesetztem Typ und URL wird mit Exit `2` abgelehnt. Sind in Auto-Mode gleichzeitig `DB2_JDBC_URL` und `ORACLE_JDBC_URL` gesetzt, wird die Mehrdeutigkeit ebenfalls abgelehnt.

## 9. Batch mit `--file`

```text
# itemtypes.txt
AM
INVOICE
CONTRACT
```

Dry-run:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --dry-run
bin/cm-retention unassign --file itemtypes.txt --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
```

Reale Ausführung:

```bash
bin/cm-retention unassign --file itemtypes.txt --yes
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --yes
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --yes
```

Die Mutationsphase läuft in einem JVM-Prozess. Bei Backfill wird eine direkte JDBC-Verbindung über den Batch wiederverwendet. Phase 1 validiert alle ItemTypes vor dem ersten Write.

Phase 2 bleibt:

```text
sequenziell
nicht atomar
echte Laufzeit-/Verifikationsfehler: fail-fast
verifizierte IBM-CM-Sekundärwarnungen: protokollieren und fortsetzen
```

Es gibt keine parallelen DB2-/Oracle-UPDATEs.

### 9.1 Unabhängige Final-Verifikation ab 0.4.2

Sobald ein echter Batch Phase 2 erreicht hat, startet der Launcher nach Ende/Abbruch der Mutationsphase eine **zweite JVM** mit einer neuen IBM-CM-Session. Diese liest jeden exakten ItemType aus der ursprünglichen Datei erneut.

Erwarteter Endzustand:

```text
unassign --file ...      -> Retention policy = -
assign --file ... POLICY -> Retention policy = POLICY
```

Beispiel:

```text
Phase 3/3: independent final verification
  Runtime   : new JVM / fresh CM session
  Operation : unassign
  Expected  : -
  Item types: 217

Final verification summary
  Verified OK         : 214
  State mismatches    : 3
  Verification errors : 0
```

Der unabhängige Verifier läuft auch nach einem echten Phase-2-Abbruch. So sieht der Administrator nach einem Teil-Batch den tatsächlichen Gesamtzustand statt nur den bis zum Fehler erreichten Prefix.

### 9.2 Retry-Datei

Bestätigte Mismatches werden mit Soll-/Ist-Policy ausgegeben und automatisch in eine `*-retry.txt` geschrieben. Diese Datei enthält nur ItemTypes, deren tatsächlicher Zustand eindeutig vom Sollzustand abweicht.

Beispiel:

```bash
bin/cm-retention unassign --file logs/cm-retention-batch-...-unassign-retry.txt --yes
```

Bei Assign wird die ursprüngliche Policy wieder angegeben:

```bash
bin/cm-retention assign --file logs/cm-retention-batch-...-assign-retry.txt AUTO_DELETE_1Y --yes
```

Bei `--backfill` wird beim Retry erneut `--backfill` verwendet. Bereits erfolgreich gesetzte Datensätze bleiben durch die NULL-Guards idempotent geschützt.

Kann der SDK-Zustand eines ItemTypes nicht zuverlässig gelesen werden, wird er als `Verification error / state unknown` gemeldet und absichtlich **nicht** in die Retry-Datei aufgenommen.

### 9.3 Audit-Log

Jeder `--file`-Aufruf erhält ein Audit-Log. Standard:

```text
<application-home>/logs/cm-retention-batch-<timestamp>-<pid>-<operation>.log
```

Optional in `.env`:

```dotenv
CM_RETENTION_LOG_DIR=/var/log/cm-retention
```

Das Log enthält:

- Tool-Version
- Start-/Endzeit
- Runtime-User und Host
- exakten Batch-Befehl
- verwendeten `.env`-Pfad
- komplette Batch-Ausgabe
- komplette Final-Verifier-Ausgabe
- `batch_rc`, `verifier_rc`, `final_rc`

Credentials werden nicht in das Audit-Log geschrieben. Kann das Log vor dem Batch nicht angelegt werden, startet die Batch-Mutation nicht.

Dry-run, reine Phase-1-Fehler oder Abbruch vor Phase 2 werden geloggt, aber nicht final-verifiziert.

### 9.4 Return-Code-Kombination

```text
Batch sauber + Verifier sauber       -> 0
Batch sauber + Verifier nicht sauber -> 6
verifizierte Sekundärwarnung(en)      -> 6
echter Mutations-/Runtimefehler       -> ursprünglicher Fehler-RC
```

Bei einem echten Mutationsfehler bleibt dessen RC erhalten; der Verifier liefert zusätzlich den aktuellen Zustand und ggf. eine Retry-Datei.

Der Backfill-Header zeigt die ausgewählte Datenbank:

```text
Batch mode (native Java runtime)
  Backfill  : yes
  Database  : DB2
  Runtime   : single JVM
```

## 10. Build und Runtime-Paket

Auf einem CM-8.7-Host mit echter `cmbicmsdk81.jar`:

```bash
./build.sh
```

Der Build führt `SelfTestMain` vor dem Packaging aus.

0.4.2 erzeugt:

```text
build/cm-retention.jar
build/cm-retention-0.4.2.jar
build/.version
build/ret-policy.properties
build/profiles/*.properties
build/cm-retention-0.4.2-runtime.tar.gz
build/SHA256SUMS-0.4.2
```

Das Runtime-Paket enthält keine IBM-SDK-/DB2-/Oracle-JARs und keine Credentials.

## 11. Installation ohne Git

```bash
tar -xzf cm-retention-0.4.2-runtime.tar.gz
cd cm-retention-0.4.2
cp .env.example .env
chmod 600 .env
vi .env
bin/cm-retention version
bin/cm-retention selftest
bin/cm-retention doctor
bin/cm-retention status
```

Der Zielserver benötigt kein Git und kein `javac`.

## 12. Empfohlene Abnahme

Allgemein:

```bash
./build.sh
bin/cm-retention version
bin/cm-retention selftest
bin/cm-retention doctor
bin/cm-retention status
bin/cm-retention policies
```

Policy Create zuerst als Dry-run und mit dem passenden DB-Profil:

DB2:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --dry-run
```

Oracle:

```bash
bin/cm-retention create profiles/auto-delete-5y-oracle.properties --dry-run
```

Backfill immer zuerst read-only:

```bash
time bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
time bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
```

Bei Legacy-ItemTypes zusätzlich einen kleinen Assign/Unassign-Batch prüfen und den Return Code kontrollieren:

```bash
bin/cm-retention unassign --file itemtypes.txt --dry-run
bin/cm-retention unassign --file itemtypes.txt --yes
echo $?
```

Prüfen:

- richtige Datenbank (`DB2` oder `Oracle`)
- richtige Schema-/Root-Tabelle
- richtige Formel
- `NULL create timestamp = 0`
- `Immediately expired after`
- Timing der Plan-Phase
- bei Batch: Phase-2-Summary und Phase-3-Final-Verifikation
- `State mismatches = 0`
- `Verification errors = 0`
- Pfad des Audit-Logs
- falls erzeugt: Inhalt der Retry-Datei
- finalen RC

## 13. Sicherheitsmodell

Normaler CM-Write:

```text
resolve -> read -> validate -> plan -> confirm/dry-run
 -> mutate -> commit -> reconnect/verify
```

Batch zusätzlich:

```text
Phase 1: alle Inputs validieren
 -> Phase 2: sequenzielle Mutationen + per-ItemType-Verifikation
 -> neue JVM / neue CM-Session
 -> Phase 3: jeden exakten ItemType erneut lesen
 -> Mismatches/unknown states explizit ausgeben
 -> bestätigte Mismatches optional als Retry-Datei
```

Backfill:

```text
full read-only plan
 -> confirm
 -> fresh metadata/fingerprint guard
 -> direct DB UPDATE
 -> DB COMMIT + residual verification
 -> fresh post-COMMIT guard
 -> CM assignment
 -> reconnect/final verification
```

Wichtige Regeln:

- bestehende Retention-/AutoDelete-Daten werden nicht überschrieben
- kein direktes Dokument-DELETE
- keine parallelen Datenbank-Writes
- verifizierte `OperationWarning` ist kein stiller Erfolg: sie bleibt sichtbar und führt im Batch-Summary zu RC `6`
- die unabhängige Final-Verifikation kann einen scheinbar sauberen Batch nachträglich auf RC `6` hochstufen
- unklare Verifikationszustände werden nicht automatisch in Retry-Dateien aufgenommen
- echte Mutations-/Runtimefehler werden durch den Final-Verifier nicht maskiert
- Exit `6` bedeutet weiterhin: Warnung bzw. Zustand nach Persistenz/Partial-Success administrativ prüfen

## 14. Exit-Codes

| Code | Bedeutung |
|---:|---|
| 0 | Erfolg / No-op / erfolgreicher Dry-run |
| 2 | CLI-/Config-/Properties-/JDBC-/Preflight-Fehler |
| 3 | IBM-CM-/Datenbank-Laufzeitfehler |
| 4 | ItemType/Policy nicht gefunden |
| 5 | unsichere/widersprüchliche Operation vor relevantem Write verweigert |
| 6 | verifizierte IBM-CM-Sekundärwarnung / unabhängige Final-Verifikationsabweichung / Verifikationswarnung / Partial-Success-Zustand nach möglicher Persistenz |

## 15. Weiterführende Dokumentation

- [README](README.md)
- [Backfill im Detail](docs/BACKFILL.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Changelog](CHANGELOG.md)
