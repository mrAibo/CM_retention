# Troubleshooting

Diese Datei sammelt bekannte Betriebs- und IBM-CM-Fehlerbilder für `cm-retention 0.4.0`.

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

`0.4.0` erkennt den direkten Backfill-Datenbanktyp am URL-Präfix:

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

Exit `6` bedeutet, dass Persistenz bereits erfolgt sein kann oder eine abschließende Verifikation nicht sauber abgeschlossen wurde.

Nicht blind wiederholen. Zuerst aktuellen Zustand lesen:

```bash
bin/cm-retention itemtype ITEMTYPE
bin/cm-retention policy POLICY
```

Bei Backfill zusätzlich denselben Dry-run erneut ausführen. Wenn der direkte DB-UPDATE bereits committed wurde, darf der zweite Lauf aufgrund der NULL-Guards die bereits gesetzten Auto-Delete-Daten nicht überschreiben.

Mit Diagnose:

```bash
CM_DEBUG=true bin/cm-retention itemtype ITEMTYPE
```

## 9. `ICM7022`, Reason Code 13, `mkdir error`

Dieses Fehlerbild betrifft IBM-CM-/Library-Server-Umgebung und ist nicht durch den Backfill-SQL-Dialekt verursacht.

In einer real beobachteten **DB2-basierten** CM-8.7-Umgebung konnte der DB2-Fenced-Benutzer das Verzeichnis für neu zu erzeugende Access-Module nicht beschreiben.

DB2-Beispiel zur kontrollierten Prüfung:

```bash
namei -l /path/to/cmgmt/ls/LSDB
getfacl -p /path/to/cmgmt /path/to/cmgmt/ls /path/to/cmgmt/ls/LSDB
```

Schreibtest nur mit dem tatsächlich für das System ermittelten Prozessbenutzer durchführen. Keine Testwerte blind nach PROD übernehmen und kein pauschales `chmod 777` setzen.

Für Oracle-basierte CM-Installationen den tatsächlich beteiligten OS-/CM-Prozess und dessen Pfade separat ermitteln; die DB2-Fenced-Annahme gilt dort nicht automatisch.

## 10. `DGL0303A` / `getViewOperator() opCode [-1]`

Typisches Fehlerbild:

```text
DGL0303A: Invalid parameter
DKAttrDefICM::getViewOperator() opCode : [-1]
```

Bei älteren Component Views wurden in realen Umgebungen Einträge mit `VIEWOPERATOR=-1` beobachtet. Der CM-8.7-SDK kann beim Neuaufbau/Aktualisieren der ItemType-View darüber stolpern.

Die vorhandenen SQL-Audit-/Repair-Beispiele in [METADATA_REPAIR.md](METADATA_REPAIR.md) wurden für **DB2** entwickelt und enthalten DB2-Syntax wie `WITH UR`. Sie sind **nicht** automatisch als Oracle-Reparaturanleitung zu verwenden.

Keine Massenänderung direkt per SQL durchführen.

## 11. Policy kann nicht gelöscht werden

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

## 12. Stacktrace einschalten

```bash
CM_DEBUG=true bin/cm-retention status
CM_DEBUG=true bin/cm-retention assign ITEMTYPE POLICY --backfill --dry-run
```

`CM_DEBUG=true` ist nur für Diagnose gedacht und kann umfangreiche technische Informationen ausgeben.
