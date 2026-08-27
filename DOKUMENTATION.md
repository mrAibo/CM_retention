# cm-retention 0.4.0 – Betriebs- und Benutzerdokumentation

## 1. Zweck

`cm-retention` verwaltet Retention-/Expiration-Policies in IBM Content Manager Enterprise Edition 8.7. Das Werkzeug bleibt bewusst klein: Java 8, vorhandenes IBM-CM-SDK, Bash-Launcher, keine GUI und kein zusätzliches CLI-Framework.

Unterstützt werden:

- Policy- und ItemType-Anzeige einschließlich Policy-Verwendung
- Erstellen von FIXED_TIME/AUTO_DELETE-Policies
- Policy-Erstellung aus `.properties`-Vorlagen
- Assign/Unassign und Dry-run
- Batch-Verarbeitung mit `--file` in einem JVM-Prozess
- Existing-Item-Backfill mit `--backfill`
- direkter Backfill auf DB2 **und Oracle 19c**
- Policy-/Root-Fingerprints und Post-COMMIT-Schutz
- Laufzeitmessungen
- reiner Regressionstest mit `selftest`

Das Tool löscht Dokumente nicht direkt und ruft `deleteExpiredItems()` nicht selbst auf.

## 2. Datenbank-Unterstützung

Normale Befehle laufen ausschließlich über das IBM-CM-SDK. Aus Sicht des Tools sind sie deshalb unabhängig davon, ob der Library Server DB2 oder Oracle verwendet:

```text
status / doctor
policies / policy
itemtypes / itemtype
create / delete
assign / unassign
--file ohne --backfill
```

Der direkte `--backfill` unterstützt ab 0.4.0:

| Library-Server-DB | Normale CM-Befehle | `--backfill` |
|---|---:|---:|
| DB2 | ja | ja |
| Oracle 19c | ja | ja |

## 3. Version und Self-Test

```bash
bin/cm-retention version
bin/cm-retention selftest
```

Erwartet:

```text
cm-retention 0.4.0
Self-test: OK (... checks)
```

Der Self-Test meldet sich nicht an Content Manager an und öffnet keine direkte DB2-/Oracle-Verbindung. Geprüft werden unter anderem Parser, Template-Erkennung, Fingerprints, `CREATETS`, DB2-/Oracle-SQL-Dialekte und Sekunden-Einheiten.

## 4. Policy-Vorlagen

Standardvorlage:

```text
ret-policy.properties
```

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

`auto-delete.max-duration=120` bedeutet **120 Sekunden**. `auto-delete.force-checkin=true` entspricht **Einchecken vor Löschen erzwingen**.

Empfohlene Verwendung:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --dry-run
bin/cm-retention create profiles/auto-delete-5y.properties
```

Mitgeliefert:

```text
profiles/auto-delete-1y.properties
profiles/auto-delete-5y.properties
profiles/auto-delete-10y.properties
```

Priorität:

```text
CLI POLICY / AGE / Optionen
  > ausgewählte Properties-Datei
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

Die maximale Auto-Delete-Laufzeit wird mit Einheit ausgegeben:

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

Ohne `--backfill` werden bestehende Retention-/Expiration-Metadaten nicht rückwirkend verändert.

## 7. Existing-Item-Backfill

Immer zuerst:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

IBM dokumentiert, dass das Anwenden einer systemgesteuerten Retention Policy auf einen vorhandenen ItemType nur neue Items/neue Versionen betrifft. Bestehende Items müssen per SQL oder eigener API-Prozedur mit Retention-/Expiration-Metadaten versehen werden.

Die logische Operation lautet:

```sql
UPDATE <SCHEMA>.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

Wichtig: die physische Erstellungszeit-Spalte heißt `CREATETS`, nicht `ICM$CREATETS`.

### 7.1 DB2

Beispiel für ein Jahr:

```text
ICM$AUTODELETEDATE = CREATETS + 1 YEAR
```

Zeitberechnung/Preflight verwenden DB2-Syntax wie:

```text
CURRENT TIMESTAMP
FETCH FIRST 1 ROW ONLY
```

### 7.2 Oracle

Beispiel für ein Jahr:

```text
ICM$AUTODELETEDATE = CREATETS + NUMTOYMINTERVAL(1, 'YEAR')
```

Generierung:

```text
YEAR  -> NUMTOYMINTERVAL(N, 'YEAR')
MONTH -> NUMTOYMINTERVAL(N, 'MONTH')
WEEK  -> NUMTODSINTERVAL(N*7, 'DAY')
DAY   -> NUMTODSINTERVAL(N, 'DAY')
```

Oracle verwendet `CURRENT_TIMESTAMP`; die kurze Exists-Abfrage verwendet `ROWNUM = 1`.

Es werden keine SQL-Fragmente aus CLI-Eingaben übernommen. Amount/Unit stammen aus der bereits validierten IBM-CM-Policy.

### 7.3 Erlaubtes Policy-Modell

Backfill akzeptiert nur:

```text
Retention type     FIXED_TIME
Retention enabled  false
Expiration enabled true
Expiration action  AUTO_DELETE
Expiration period  > 0
Unit               YEAR/MONTH/WEEK/DAY
```

Andere Fälle werden vor dem Write abgelehnt.

### 7.4 Root-Auflösung

Der Anwender gibt keine `ICMUT...`-Tabelle an. Das Tool ermittelt Root-Komponente und Segment aus:

```text
ICMSTCOMPDEFS
ICMSTITEMTYPEDEFS
```

und leitet daraus die physische Tabelle ab:

```text
ICMUT<COMPONENTTYPEID><SEGMENTID>
```

Schema und Tabellenname werden strikt validiert. Multi-Segment-Fälle bleiben fail-closed.

### 7.5 Detailed Plan und Fast Path

Phase 1/Dry-run berechnet die Berichtszähler in **einem aggregierten SELECT** pro Root-Tabelle.

Phase 2 wiederholt diesen vollständigen Scan nicht. Vor dem Write werden frisch geprüft:

- aktuelle ItemType-Zuweisung
- vollständiger Policy-Fingerprint
- ItemTypeID / ComponentTypeID / SegmentID / Root-Tabelle
- Existenz eines eligible Rows mit `NULL CREATETS`

### 7.6 Policy-/Root-Fingerprints

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

## 8. Backfill-Konfiguration

Normale Non-Backfill-Befehle benötigen diese Werte nicht.

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

IBM CM 8.7 benötigt für Oracle `ojdbc8.jar`. Der Launcher sucht den Treiber unter anderem unter `$ORACLE_HOME/jdbc/lib` und in bekannten IBM/WAS-Pfaden. Ein explizites `BACKFILL_JDBC_JAR` ist für Produktion am eindeutigsten.

Für Oracle ist ein expliziter JDBC-URL erforderlich. Listener/Service werden **nicht** aus `CM_DATABASE` geraten.

Auch folgende Oracle-Aliase werden akzeptiert:

```text
ORACLE_JDBC_URL
ORACLE_USER
ORACLE_PASSWORD
ORACLE_SCHEMA
ORACLE_JDBC_JAR
```

Bevorzugt werden jedoch die neutralen `BACKFILL_*`-Namen.

### 8.3 Auto-Detection

```dotenv
BACKFILL_DB_TYPE=auto
```

oder ein fehlender Typ erkennt die Datenbank am URL-Präfix:

```text
jdbc:db2:...     -> DB2
jdbc:oracle:...  -> Oracle
```

Ein Widerspruch zwischen fest gesetztem Typ und URL wird mit Exit `2` abgelehnt.

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
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
```

Reale Ausführung:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --yes
```

Der gesamte Batch läuft in einem JVM-Prozess. Bei Backfill wird eine direkte JDBC-Verbindung über den Batch wiederverwendet. Phase 1 validiert alle ItemTypes vor dem ersten Write. Phase 2 bleibt:

```text
sequenziell
fail-fast
nicht atomar
```

Es gibt keine parallelen DB2-/Oracle-UPDATEs.

Der Header zeigt die ausgewählte Datenbank:

```text
Batch mode (native Java runtime)
  Backfill  : yes
  Database  : Oracle
  Runtime   : single JVM
```

## 10. Build und Runtime-Paket

Auf einem CM-8.7-Host mit echter `cmbicmsdk81.jar`:

```bash
./build.sh
```

Der Build führt `SelfTestMain` vor dem Packaging aus.

0.4.0 erzeugt:

```text
build/cm-retention.jar
build/cm-retention-0.4.0.jar
build/.version
build/ret-policy.properties
build/profiles/auto-delete-1y.properties
build/profiles/auto-delete-5y.properties
build/profiles/auto-delete-10y.properties
build/cm-retention-0.4.0-runtime.tar.gz
build/SHA256SUMS-0.4.0
```

Das Runtime-Paket enthält keine IBM-SDK-/DB2-/Oracle-JARs und keine Credentials.

## 11. Installation ohne Git

```bash
tar -xzf cm-retention-0.4.0-runtime.tar.gz
cd cm-retention-0.4.0
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
bin/cm-retention policy AUTO_DELETE_1Y
```

Backfill immer zuerst read-only:

```bash
time bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
time bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
```

Prüfen:

- richtige Datenbank (`DB2` oder `Oracle`)
- richtige Schema-/Root-Tabelle
- richtige Formel
- `NULL create timestamp = 0`
- `Immediately expired after`
- Timing der Plan-Phase

## 13. Sicherheitsmodell

Normaler CM-Write:

```text
resolve -> read -> validate -> plan -> confirm/dry-run
 -> mutate -> commit -> reconnect/verify
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
- Exit `6` bedeutet: Persistenz/Partial-Success möglich; Zustand prüfen

## 14. Exit-Codes

| Code | Bedeutung |
|---:|---|
| 0 | Erfolg / No-op / erfolgreicher Dry-run |
| 2 | CLI-/Config-/Properties-/JDBC-/Preflight-Fehler |
| 3 | IBM-CM-/Datenbank-Laufzeitfehler |
| 4 | ItemType/Policy nicht gefunden |
| 5 | unsichere/widersprüchliche Operation vor relevantem Write verweigert |
| 6 | Verifikationswarnung / Partial-Success / Zustand nach möglicher Persistenz prüfen |

## 15. Weiterführende Dokumentation

- [README](README.md)
- [Backfill im Detail](docs/BACKFILL.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Changelog](CHANGELOG.md)
