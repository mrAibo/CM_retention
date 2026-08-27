# Troubleshooting

Diese Datei sammelt bekannte Betriebs- und IBM-CM-Fehlerbilder für `cm-retention 0.4.2`.

## 1. `.env` wird abgelehnt

```text
ERROR: insecure permissions on .../.env: 644
```

Lösung:

```bash
chmod 600 .env
stat -c '%A %a %U:%G %n' .env
```

## 2. Konfigurationsdatei fehlt

```text
ERROR: configuration file not found: ...
```

Prüfen:

```bash
ls -l .env
```

Oder explizit:

```bash
bin/cm-retention --env /secure/cm-test.env status
```

## 3. Falscher oder unbekannter CM-Alias

Wenn `CM_DATABASE` nicht dem auf dem Host konfigurierten IBM-CM-Library-Server-Alias entspricht, schlägt die CM-SDK-Verbindung fehl.

Prüfen:

- richtige `.env` ausgewählt?
- korrekter Library-Server-Alias?
- richtige IBM-CM-Clientkonfiguration aktiv?
- richtige Benutzerkennung?

Beginnen mit:

```bash
bin/cm-retention --env /secure/cm-test.env status
bin/cm-retention --env /secure/cm-test.env doctor
```

## 4. `--backfill`: Datenbanktyp/URL widersprüchlich

Beispiel:

```text
ERROR: BACKFILL_DB_TYPE=oracle conflicts with JDBC URL jdbc:db2:...
```

Der direkte Backfill-Datenbanktyp wird am URL-Präfix erkannt:

```text
jdbc:db2:...     -> DB2
jdbc:oracle:...  -> Oracle
```

Entweder `BACKFILL_DB_TYPE=auto` verwenden/den Typ weglassen oder Typ und URL konsistent konfigurieren.

## 5. `--backfill`: JDBC-Treiber fehlt

Typisch:

```text
ERROR: DB2 JDBC driver not found ...
```

oder:

```text
ERROR: Oracle JDBC driver not found ...
```

Bevorzugt explizit konfigurieren:

DB2:

```dotenv
BACKFILL_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

Oracle:

```dotenv
ORACLE_HOME=/u01/app/oracle/product/19.0.0/dbhome_1
BACKFILL_JDBC_JAR=/u01/app/oracle/product/19.0.0/dbhome_1/jdbc/lib/ojdbc8.jar
```

Prüfen:

```bash
ls -l "$BACKFILL_JDBC_JAR"
```

Der Runtime-Tarball enthält absichtlich keinen DB2-/Oracle-JDBC-Treiber.

## 6. Oracle: JDBC URL fehlt

Oracle-Backfill benötigt einen expliziten URL. Beispiel:

```dotenv
BACKFILL_DB_TYPE=oracle
BACKFILL_JDBC_URL=jdbc:oracle:thin:@//dbhost.example:1521/LSDB
BACKFILL_USER=icmconct
BACKFILL_PASSWORD=CHANGE_ME
BACKFILL_SCHEMA=ICMADMIN
```

Das Tool leitet Listener/Port/Service bewusst nicht aus `CM_DATABASE` ab.

## 7. Direkter DB-Fehler bei `--backfill`

Die Ausgabe beginnt mit:

```text
DATABASE ERROR
```

und zeigt Message, SQLSTATE und Error Code.

Prüfen:

- zeigt Dry-run die erwartete Datenbank (`DB2` oder `Oracle`)?
- korrekter JDBC URL?
- korrektes Schema?
- hat der direkte DB-Benutzer SELECT auf CM-Metadaten/Root-Tabellen?
- hat er UPDATE auf die betroffene `ICMUT...`-Root-Tabelle?
- DB2-/Oracle-Log für denselben Zeitpunkt prüfen.

Immer zunächst:

```bash
bin/cm-retention assign ITEMTYPE POLICY --backfill --dry-run
```

## 8. Exit-Code 6 nach Assign/Unassign/Backfill

Exit `6` bedeutet, dass mindestens eine verifizierte IBM-CM-Sekundärwarnung, eine unabhängige Final-Verifikationsabweichung oder ein Zustand nach möglicher Persistenz aufgetreten ist.

Bei einem Einzelbefehl nicht blind wiederholen. Zuerst aktuellen Zustand lesen:

```bash
bin/cm-retention itemtype ITEMTYPE
bin/cm-retention policy POLICY
```

Bei Backfill zusätzlich denselben Dry-run erneut ausführen. Wenn der direkte DB-UPDATE bereits committed wurde, darf der zweite Lauf aufgrund der NULL-Guards die bereits gesetzten Auto-Delete-Daten nicht überschreiben.

Ab 0.4.1 kann ein `--file`-Batch vollständig abgearbeitet werden und trotzdem Exit `6` zurückgeben, wenn verifizierte IBM-CM-Sekundärwarnungen aufgetreten sind.

Ab 0.4.2 kommt zusätzlich die unabhängige Abschlussprüfung hinzu. Ein Batch, dessen Mutationsphase sauber aussah, wird auf RC `6` hochgestuft, wenn die zweite JVM den gewünschten Endzustand nicht für alle ItemTypes bestätigen kann.

Mit Diagnose:

```bash
CM_DEBUG=true bin/cm-retention itemtype ITEMTYPE
```

## 9. Batch meldet Final-Verifikations-Mismatches

Typische Ausgabe:

```text
Phase 3/3: independent final verification
Final verification summary
  Verified OK         : 214
  State mismatches    : 3
  Verification errors : 0

Confirmed state mismatches:
  - ITEM_A expected=- actual=AUTO_DELETE_1Y
  - ITEM_B expected=- actual=AUTO_DELETE_1Y
```

Das bedeutet: Die zweite JVM hat mit einer frischen CM-Session den tatsächlichen Policy-Zustand erneut gelesen und mindestens einen eindeutig falschen Endzustand gefunden.

Der Launcher erzeugt zusätzlich eine Datei wie:

```text
logs/cm-retention-batch-20260827-123456-12345-unassign-retry.txt
```

Sie enthält nur **bestätigte Mismatches** und kann direkt erneut verwendet werden:

```bash
bin/cm-retention unassign --file logs/cm-retention-batch-...-unassign-retry.txt --yes
```

Bei Assign entsprechend mit der ursprünglichen Policy:

```bash
bin/cm-retention assign --file logs/cm-retention-batch-...-assign-retry.txt AUTO_DELETE_1Y --yes
```

Bei `--backfill` muss beim Retry auch wieder `--backfill` angegeben werden. Der Backfill bleibt aufgrund der bestehenden NULL-Guards idempotent für bereits erfolgreich gesetzte Datensätze.

## 10. Final-Verifier meldet `Verification errors`

Beispiel:

```text
Verification errors (state unknown; not added to retry file):
  - AM: DGL0303A: ...
```

Das ist absichtlich **nicht** dasselbe wie ein bestätigter Mismatch. Der Verifier konnte den tatsächlichen Zustand dieses ItemTypes nicht sicher lesen. Solche Einträge werden daher nicht in `retry.txt` geschrieben.

Vorgehen:

1. Audit-Log prüfen.
2. ItemType einzeln lesen:

```bash
bin/cm-retention itemtype AM
```

3. IBM-CM-Log für denselben Zeitpunkt prüfen.
4. Erst nach bestätigtem Ist-Zustand erneut mutieren.

## 11. Batch-Audit-Log fehlt oder ist nicht beschreibbar

Standardpfad:

```text
<application-home>/logs
```

Optional in `.env`:

```dotenv
CM_RETENTION_LOG_DIR=/var/log/cm-retention
```

Der Runtime-Benutzer muss das Verzeichnis erstellen/beschreiben können. Kann der Launcher vor einer Batch-Ausführung kein Audit-Log anlegen, wird die Operation vor der Batch-Mutation mit Exit `2` verweigert.

Prüfen:

```bash
ls -ld /var/log/cm-retention
id
```

Kein `chmod 777` verwenden. Verzeichnis und Logs sollen nur für den Runtime-Benutzer zugänglich sein.

## 12. `ICM7022`, Reason Code 13, `mkdir error`

Dieses Fehlerbild betrifft IBM-CM-/Library-Server-Umgebung und ist nicht durch den Backfill-SQL-Dialekt verursacht.

In einer real beobachteten **DB2-basierten** CM-8.7-Umgebung konnte der DB2-Fenced-Benutzer das Verzeichnis für neu zu erzeugende Access-Module nicht beschreiben.

DB2-Beispiel zur kontrollierten Prüfung:

```bash
namei -l /path/to/cmgmt/ls/LSDB
getfacl -p /path/to/cmgmt /path/to/cmgmt/ls /path/to/cmgmt/ls/LSDB
```

Schreibtest nur mit dem tatsächlich für das System ermittelten Prozessbenutzer durchführen. Keine Testwerte blind nach PROD übernehmen und kein pauschales `chmod 777` setzen.

Für Oracle-basierte CM-Installationen den tatsächlich beteiligten OS-/CM-Prozess und dessen Pfade separat ermitteln; die DB2-Fenced-Annahme gilt dort nicht automatisch.

## 13. `DGL0303A` / `getViewOperator() opCode [-1]`

Typisches Fehlerbild:

```text
DGL0303A: Ungültiger Parameter.
DKAttrDefICM::getViewOperator() opCode : [-1]
```

Bei älteren Component Views wurden in realen Umgebungen Einträge mit `VIEWOPERATOR=-1` beobachtet. Der CM-8.7-SDK kann beim Neuaufbau/Aktualisieren der ItemType-View darüber stolpern.

Wichtig ist die Unterscheidung zwischen **Persistenzfehler** und **Sekundärfehler nach Persistenz**.

Wenn `cm-retention` meldet:

```text
WARNING: Policy AUTO_DELETE_1Y was removed from itemtype AM,
but IBM CM reported a secondary error after persisting the change.
```

hat `CmService` nach dem IBM-CM-Fehler eine neue Session geöffnet und den gewünschten Persistenzzustand bestätigt. Ein Einzelbefehl liefert trotzdem Exit `6`.

Ab 0.4.1 gilt im Batch:

```text
OperationWarning + Persistenz verifiziert
    -> Warning ausgeben
    -> ItemType als verified zählen
    -> mit nächstem ItemType fortfahren
    -> Batch am Ende RC 6

Zustand nicht verifizierbar / anderer Fehler
    -> sofort stoppen
```

Ab 0.4.2 wird anschließend zusätzlich jeder ItemType durch die unabhängige zweite JVM erneut geprüft. Damit ist eine lokale `OperationWarning`-Verifikation nicht mehr die letzte Kontrolle des Batch-Endzustands.

Die vorhandenen SQL-Audit-/Repair-Hinweise in [METADATA_REPAIR.md](METADATA_REPAIR.md) wurden für **DB2** entwickelt und enthalten DB2-Syntax wie `WITH UR`. Sie sind **nicht** automatisch als Oracle-Reparaturanleitung zu verwenden.

Keine pauschale Massenänderung `VIEWOPERATOR=-1 -> 0` direkt per SQL durchführen. Die Filtersemantik einer Component View muss vor einer Metadatenreparatur verstanden sein.

## 14. Bereits zugewiesene AUTO_DELETE-Policy nachträglich ändern

In der Praxis kann das Ändern einer bereits vielen ItemTypes zugewiesenen AUTO_DELETE-Policy dazu führen, dass per-ItemType Automatic-Delete-Tasks/Schedules nicht so neu aufgebaut werden wie erwartet.

Besonders vorsichtig behandeln:

```text
expiration.age
auto-delete.schedule
auto-delete.commit-count
auto-delete.max-items
auto-delete.max-duration
auto-delete.force-checkin
```

Empfohlener kontrollierter Ablauf:

```text
1. betroffene ItemTypes dry-run prüfen
2. Policy unassignen
3. Policy ändern oder neue Policy erstellen
4. Policy erneut assignen
5. Automatic-Delete-Tasks/Schedule prüfen
6. --backfill nur verwenden, wenn Existing-Item-Daten tatsächlich gesetzt/repariert werden müssen
```

Eine normale Neuzuweisung berechnet vorhandene `ICM$AUTODELETEDATE`-Werte nicht automatisch neu.

## 15. Policy kann nicht gelöscht werden

```text
ERROR: Policy is assigned to ... itemtype(s)
```

Verwendung anzeigen:

```bash
bin/cm-retention policy POLICY_NAME
```

Zuweisung gezielt entfernen:

```bash
bin/cm-retention unassign ITEMTYPE --dry-run
bin/cm-retention unassign ITEMTYPE
```

Danach:

```bash
bin/cm-retention delete POLICY_NAME --dry-run
bin/cm-retention delete POLICY_NAME
```

## 16. Stacktrace einschalten

```bash
CM_DEBUG=true bin/cm-retention status
CM_DEBUG=true bin/cm-retention assign ITEMTYPE POLICY --backfill --dry-run
```

`CM_DEBUG=true` ist nur für Diagnose gedacht und kann umfangreiche technische Informationen ausgeben.
