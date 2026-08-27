# cm-retention 0.3.5 – Betriebs- und Benutzerdokumentation

## 1. Zweck

`cm-retention` verwaltet Retention-/Expiration-Policies in IBM Content Manager Enterprise Edition 8.7. Das Werkzeug bleibt bewusst klein: Java 8, vorhandenes IBM-CM-SDK, Bash-Launcher, keine GUI und kein zusätzliches CLI-Framework.

Unterstützt werden:

- Policy- und ItemType-Anzeige einschließlich Policy-Verwendung
- Erstellen von FIXED_TIME/AUTO_DELETE-Policies
- vollständige Policy-Erstellung aus Properties-Vorlagen
- automatische Erkennung einer lesbaren `.properties`-Datei bei `create`
- mehrere Vorlagen unter `profiles/`
- Assign/Unassign und Dry-run
- Datei-Batch mit `--file` in einem nativen Java-Batch-Runtime
- Existing-Item-Backfill mit `--backfill`
- Policy-/Root-Fingerprint-Schutz für Backfill
- Laufzeitmessungen für Batch/Backfill
- reiner Regressionstest mit `selftest`

## 2. Version und Self-Test

```bash
bin/cm-retention version
bin/cm-retention selftest
```

Erwartet:

```text
cm-retention 0.3.5
Self-test: OK (... checks)
```

`selftest` benötigt die installierten IBM-CM-SDK-Klassen, meldet sich aber **nicht** an Content Manager an und verbindet sich **nicht** mit DB2. Er prüft Parser-/Template-Logik, Policy-/Root-Fingerprints, generiertes Backfill-SQL einschließlich `CREATETS` und die Sekunden-Einheit.

## 3. Policy-Vorlagen

Standard:

```text
ret-policy.properties
```

Jede verwendete Vorlage enthält mindestens:

```properties
RET_POLICY_NAME=AUTO_DELETE_1Y
expiration.age=1y
```

Vollständiges Modell:

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

`auto-delete.max-duration` wird in **Sekunden** angegeben. `120` = 120 Sekunden = 2 Minuten. `auto-delete.force-checkin=true` bedeutet **Einchecken vor Löschen erzwingen**.

Empfohlene Kurzform:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --dry-run
bin/cm-retention create profiles/auto-delete-5y.properties
```

Explizit bleibt möglich:

```bash
bin/cm-retention create --properties profiles/auto-delete-5y.properties
```

Die Auto-Erkennung greift nur bei einer existierenden, regulären, lesbaren `.properties`-Datei, die das einzige Positionsargument von `create` ist. Für positionale Name-/AGE-Overrides wird weiterhin `--properties FILE` verwendet.

Mitgeliefert:

```text
profiles/auto-delete-1y.properties
profiles/auto-delete-5y.properties
profiles/auto-delete-10y.properties
```

Priorität:

```text
CLI POLICY / AGE / Optionen
  > ausgewählte Properties-Vorlage
  > ret-policy.properties
  > eingebaute Defaults
```

Ab 0.3.5 gibt es keinen versteckten Environment-Override für den Policy-Template-Pfad mehr. Andere Templates werden über `--properties FILE` bzw. die automatische Datei-Kurzform gewählt.

## 4. Policies und ItemTypes anzeigen

```bash
bin/cm-retention policies
bin/cm-retention policy AUTO_DELETE_5Y
bin/cm-retention itemtypes
bin/cm-retention itemtype AM
```

`policies` zeigt pro Policy die Anzahl der zugewiesenen ItemTypes. Ab 0.3.5 werden Policies und ItemTypes dafür jeweils gesammelt geladen; die Usage-Counts werden im Speicher gebildet statt pro Policy erneut abzufragen.

`policy POLICY` zeigt zusätzlich die konkreten ItemTypes:

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

Die Dauer wird in Policy- und ItemType-Details jetzt eindeutig ausgegeben:

```text
Auto-delete max. duration:  120 sec
```

## 5. Normaler Assign/Unassign

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --dry-run
bin/cm-retention assign AM AUTO_DELETE_1Y
bin/cm-retention unassign AM --dry-run
bin/cm-retention unassign AM
```

Ohne `--backfill` werden bestehende Dokument-Metadaten nicht verändert. Writes behalten die bestehende stale-state- und reconnect/persisted-state-Verifikation.

## 6. Existing-Item-Backfill

Immer zuerst:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

Die korrekte Formel lautet:

```text
ICM$AUTODELETEDATE = CREATETS + <Policy-Frist>
```

Beispiel:

```text
Formula : ICM$AUTODELETEDATE = CREATETS + 1 YEAR
```

Entsprechendes SQL:

```sql
UPDATE ICMADMIN.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + 1 YEAR
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

Wichtig: die physische Spalte heißt `CREATETS`, nicht `ICM$CREATETS`.

### Laufzeitmodell ab 0.3.5

Auch der einzelne Backfill läuft jetzt vollständig in **einem JVM-Prozess**. Die frühere Launcher-Kette aus getrennten `plan`/`apply`/`assign`/`verify`-JVMs wurde entfernt. `SingleBackfillMain` und Batch benutzen denselben `BackfillWorkflow`.

### Detailed plan vs. Write-Preflight

Phase 1/Dry-run berechnet die sieben Plan-Zähler weiterhin in **einem aggregierten SELECT** pro Root-Tabelle.

Vor dem Write wird dieser große Tabellen-Scan nicht erneut ausgeführt. Stattdessen werden frisch geprüft:

- ItemType-Zuweisung
- vollständiger Policy-Fingerprint
- ItemTypeID / ComponentTypeID / SegmentID / ICMUT-Tabelle
- Existenz eines problematischen eligible Rows mit `NULL CREATETS`

Die letzte Prüfung ist fail-fast und benötigt keinen zweiten vollständigen Statistik-Scan.

### Policy- und Root-Fingerprint

Phase 1 speichert einen unveränderlichen Snapshot der Policy:

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

Zusätzlich wird die Root-Identität gespeichert:

```text
ItemTypeID
ComponentTypeID
SegmentID
ICMUT table
```

Diese Fingerprints werden geprüft:

1. **vor DB2 UPDATE** – Änderung => Exit `5`, kein Backfill-Write;
2. **nach DB2 COMMIT und vor Policy Assign** – Änderung nach einem tatsächlich geschriebenen Backfill => Exit `6`; die geänderte Policy wird nicht zugewiesen;
3. **bei finaler Verifikation** – Änderung => Exit `6`.

Damit kann eine Policy nicht still zwischen Berechnung der vorhandenen Objekte und ihrer Zuweisung auf andere Semantik umgestellt werden.

Reale Ausführung:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

Die Ausgabe enthält jetzt Messwerte, zum Beispiel:

```text
Plan timing                : 1.234 sec
Timing                    : preflight ... / DB2 ... / post-commit guard ... / CM ... / verify ... / total ...
```

Siehe auch [docs/BACKFILL.md](docs/BACKFILL.md).

## 7. Batch mit `--file`

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

### Phase 1

Der gesamte Auftrag läuft in einem JVM-Prozess. Ab 0.3.5 wird die komplette ItemType-Metadatenkollektion einmal geladen und in eine Map überführt. Dadurch ersetzt ein Bulk-Read die früheren `retrieveEntity()`-Round-trips für jede Zeile der Datei.

Die Ziel-Policy wird einmal frisch geladen und fingerprinted. Bei Backfill wird eine DB2-JDBC-Verbindung über den gesamten Batch wiederverwendet.

Alle ItemTypes werden validiert, bevor der erste Write erlaubt wird.

### Phase 2

Nach einer einzigen Bestätigung wird die CM-Session aus Phase 1 bewusst verworfen. Writes bleiben:

```text
sequenziell
fail-fast
nicht atomar
```

Es gibt weiterhin **keine parallelen DB2 UPDATEs oder IBM-CM-Writes**. Das vermeidet unkontrollierten Druck auf Transaction Log, Locks und I/O.

Für jedes ItemType erfolgt vor dem tatsächlichen Write eine frische Zustands-/Fingerprint-Prüfung. Nach jedem CM-Write bleibt die reconnect-basierte Persistenzprüfung erhalten.

Die Ausgabe enthält echte Timings:

```text
Phase 1 timing: ... sec
Item timing: ... sec
Phase 2 timing: ... sec
Total timing  : ... sec
```

Diese Werte sollen zuerst auf TEST/PROD gemessen werden, bevor eine spätere begrenzte Parallelisierung überhaupt erwogen wird.

## 8. DB2-Konfiguration für `--backfill`

Optionale `.env`-Werte:

```dotenv
DB2_DATABASE=LSDB
DB2_JDBC_URL=jdbc:db2:LSDB
DB2_USER=icmadmin
DB2_PASSWORD=CHANGE_ME
DB2_SCHEMA=ICMADMIN
DB2_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

Ohne DB2-spezifische Werte werden CM-Datenbank/User/Passwort als Defaults verwendet. Ein Type-4-URL ist ebenfalls möglich:

```dotenv
DB2_JDBC_URL=jdbc:db2://dbhost.example:50000/LSDB
```

## 9. Sicherheitsmodell

Normaler CM-Write:

```text
resolve -> read -> validate -> plan -> confirm/dry-run
 -> mutate -> commit -> reconnect/verify
```

Backfill 0.3.5:

```text
detailed plan + fingerprints
 -> confirm
 -> fresh ItemType/Policy/Root guard
 -> cheap NULL-CREATETS preflight
 -> DB2 UPDATE
 -> DB2 COMMIT + residual verification
 -> fresh post-COMMIT fingerprint guard
 -> IBM CM policy assignment
 -> reconnect
 -> final Policy/Root/assignment/DB2 verification
```

DB2 Backfill und IBM-CM-Assignment sind keine verteilte gemeinsame Transaktion. Deshalb werden mögliche Partial-Success-Zustände bewusst mit Exit `6` sichtbar gemacht.

## 10. Build

Auf einem IBM-CM-8.7-Host mit echter `cmbicmsdk81.jar`:

```bash
./build.sh
```

Der Build führt nach `javac` automatisch `SelfTestMain` aus. Erst bei erfolgreichem Self-Test werden JAR und Runtime-Paket erstellt.

Version 0.3.5 erzeugt:

```text
build/cm-retention.jar
build/cm-retention-0.3.5.jar
build/.version
build/ret-policy.properties
build/profiles/auto-delete-1y.properties
build/profiles/auto-delete-5y.properties
build/profiles/auto-delete-10y.properties
build/cm-retention-0.3.5-runtime.tar.gz
build/SHA256SUMS-0.3.5
```

Das Runtime-Paket enthält zusätzlich `tests/selftest.sh`.

## 11. Installation ohne Git

Nach dem Build wird `build/cm-retention-0.3.5-runtime.tar.gz` auf den Zielserver übertragen. Dort:

```bash
tar -xzf cm-retention-0.3.5-runtime.tar.gz
cd cm-retention-0.3.5
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

```bash
./build.sh
bin/cm-retention version
bin/cm-retention selftest
bin/cm-retention doctor
bin/cm-retention status
bin/cm-retention policies
bin/cm-retention policy AUTO_DELETE_1Y
bin/cm-retention itemtypes
bin/cm-retention create profiles/auto-delete-1y.properties --dry-run
time bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --dry-run
time bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
```

Im Create-Plan muss erscheinen:

```text
Name         : AUTO_DELETE_1Y
Expiration   : 1 year
Limits       : 5000 items / 120 sec
Force checkin: yes
```

Beim Policy-/ItemType-Detail muss `Auto-delete max. duration` mit `sec` ausgegeben werden. Beim Batch-Dry-run muss der Header `Batch mode (native Java runtime)` / `Runtime : single JVM` und eine Phase-1-Zeit erscheinen.

## 13. Exit-Codes

| Code | Bedeutung |
|---:|---|
| 0 | Erfolg / No-op / erfolgreicher Dry-run |
| 2 | CLI-/Config-/Properties-/Preflight-Fehler |
| 3 | IBM-CM-/DB2-Laufzeitfehler |
| 4 | ItemType/Policy nicht gefunden |
| 5 | unsichere/widersprüchliche Operation vor relevantem Write verweigert |
| 6 | Verifikationswarnung / Partial-Success / Änderung nach möglicher Persistenz |
