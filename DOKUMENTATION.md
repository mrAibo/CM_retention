# cm-retention 0.2.1 – Betriebs- und Benutzerdokumentation

## 1. Zweck

`cm-retention` verwaltet Retention-/Expiration-Policies in IBM Content Manager Enterprise Edition 8.7 über die IBM-CM-Java-API.

Das Werkzeug ist bewusst klein gehalten und für Administratoren gedacht. Es bietet kurze direkte Befehle, einen einfachen interaktiven Modus, Dry-run, Status/Doctor sowie einen kontrollierten Datei-Batch für mehrere ItemTypes.

Nicht Bestandteil des Werkzeugs sind:

- direkte Dokumentlöschung
- manueller Aufruf von `deleteExpiredItems()`
- automatisches Backfill bestehender Dokumente
- Schreibzugriffe auf IBM-CM-Systemtabellen
- implizite Bulk-Logik ohne Vorprüfung
- GUI/TUI-Frameworks

## 2. Sicherheitsmodell

Jede einzelne Write-Operation folgt:

```text
1. Ziel auflösen
2. aktuellen Zustand lesen
3. Eingaben/Voraussetzungen validieren
4. Änderungsplan anzeigen
5. Dry-run / Bestätigung behandeln
6. IBM-CM-Änderung ausführen
7. commit
8. Verbindung/Zustand erneut prüfen
9. Ergebnis und Exit-Code melden
```

Bei Assign/Unassign bleibt die besondere Reconnect-/Persistenzprüfung erhalten. Meldet IBM CM nach einer möglicherweise bereits gespeicherten Änderung einen sekundären Fehler, wird der tatsächliche Zustand erneut gelesen. Ist die gewünschte Änderung bereits persistent, endet das Tool bewusst mit Exit-Code `6` statt mit einem falschen sauberen Erfolg.

## 3. Version 0.2.1

Neu gegenüber 0.2.0:

- `--file` für mehrere ItemTypes bei `assign` und `unassign`
- vollständige Vorvalidierung aller Batch-Einträge vor der ersten Mutation
- sequenzielle Fail-fast-Ausführung
- vorkompiliertes versionsgebundenes JAR im Build
- transportierbares Runtime-TAR.GZ für Server ohne Git und ohne `javac`
- SHA-256-Datei für versioniertes JAR und Runtime-Paket
- Build-Version wird direkt aus `CmRetention.VERSION` gelesen

## 4. Voraussetzungen

Typische Umgebung:

```text
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Runtime benötigt:

```text
${IBMCMROOT}/lib/cmbicmsdk81.jar
${IBMCMROOT}/lib/
${IBMCMROOT}/cmgmt/
${JAVA_HOME}/bin/java
```

Für einen Source-Build zusätzlich:

```text
${JAVA_HOME}/bin/javac
${JAVA_HOME}/bin/jar
```

Empfohlener Runtime-Benutzer: `ibmcmadm`.

## 5. Konfiguration

Einzelsystem:

```bash
cp .env.example .env
chmod 600 .env
vi .env
```

Beispiel:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=change-me
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Die `.env` wird direkt geparst und nicht als Shell-Datei ausgeführt. Passwörter gehören nicht auf die Kommandozeile.

### TEST und PROD

Empfohlen:

```text
.env.test
.env.prod
```

```bash
chmod 600 .env.test .env.prod
bin/cm-retention --env .env.test status
bin/cm-retention --env .env.prod status
```

TEST und PROD immer als dedizierte Ziele behandeln. Vor einem PROD-Write zuerst `status` ausführen.

## 6. Build auf einem IBM-CM-Host

```bash
./build.sh
```

Die Version wird aus:

```java
CmRetention.VERSION
```

gelesen und in das JAR-Manifest geschrieben.

Für 0.2.1 entstehen:

```text
build/cm-retention.jar
build/cm-retention-0.2.1.jar
build/.version
build/cm-retention-0.2.1-runtime.tar.gz
build/SHA256SUMS-0.2.1
```

Prüfen:

```bash
cat build/.version
ls -lh build/cm-retention*.jar build/*runtime.tar.gz
```

## 7. Deployment ohne Git

Dieser Weg ist für kontrollierte CM-Server ohne Git vorgesehen.

### 7.1 Auf kompatiblem CM-Host bauen

```bash
./build.sh
```

### 7.2 Runtime-Archiv übertragen

```text
build/cm-retention-0.2.1-runtime.tar.gz
```

Das Archiv enthält **keine** IBM-SDK-Dateien und keine Zugangsdaten.

### 7.3 Auf Zielsystem entpacken

```bash
cd /home/ibmcmadm
tar -xzf cm-retention-0.2.1-runtime.tar.gz
cd cm-retention-0.2.1
```

Auf dem Ziel sind weder Git noch `javac` erforderlich.

### 7.4 Konfigurieren

```bash
cp .env.example .env
chmod 600 .env
vi .env
```

### 7.5 Abnehmen

```bash
cat build/.version
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
bin/cm-retention policies
bin/cm-retention itemtypes
```

Für bestmögliche Binärkompatibilität sollte das Runtime-Paket gegen denselben IBM-CM-8.7-Level/Fixpack wie die Zielsysteme gebaut werden.

## 8. Hauptbefehle

```text
cm-retention status
cm-retention policies
cm-retention policy [POLICY]
cm-retention itemtypes
cm-retention itemtype [ITEMTYPE]
cm-retention create [POLICY] [AGE]
cm-retention assign [ITEMTYPE] [POLICY]
cm-retention assign --file ITEMTYPES.txt POLICY
cm-retention unassign [ITEMTYPE]
cm-retention unassign --file ITEMTYPES.txt
cm-retention delete [POLICY]
cm-retention doctor
```

Ohne Argumente startet bei einem TTY der kleine interaktive Admin-Modus.

## 9. Status und Doctor

```bash
bin/cm-retention status
bin/cm-retention doctor
```

`status` zeigt u. a. Konfiguration, Java-Version, IBM-CM-API, Datastore sowie Anzahl Policies und ItemTypes.

`doctor` prüft zunächst Launcher/Runtime und anschließend Login sowie Policy-/ItemType-API.

## 10. Create

Normalfall:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y
```

Defaults:

```text
schedule       0 2 * * *   (täglich 02:00)
commit-count   100
max-items      5000
max-duration   120 Minuten
force-checkin  false
```

Dry-run:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y --dry-run
```

Automation:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y --yes
```

Advanced:

```bash
bin/cm-retention create RET_10Y 10y \
  --schedule "0 4 * * *" \
  --commit-count 200 \
  --max-items 10000 \
  --max-duration 180
```

## 11. Assign / Unassign – Einzeloperation

Assign Dry-run:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y --dry-run
```

Interaktiv:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y
```

Non-TTY:

```bash
bin/cm-retention assign INVOICE AUTO_DELETE_5Y --yes
```

Unassign entsprechend:

```bash
bin/cm-retention unassign INVOICE --dry-run
bin/cm-retention unassign INVOICE
bin/cm-retention unassign INVOICE --yes
```

Assign und Unassign sind idempotent. Bereits gewünschter Zustand wird als erfolgreicher No-op behandelt.

## 12. Datei-Batch mit `--file`

`--file` ist ausschließlich für `assign` und `unassign` vorgesehen.

### 12.1 Dateiformat

```text
# Wave 1
INVOICE
CONTRACT
CUSTOMER_DOC

MAIL_ARCHIVE
```

Regeln:

- ein exakter ItemType-Name pro Zeile
- Leerzeilen werden ignoriert
- Zeilen mit `#` am Anfang sind Kommentare
- führende/nachgestellte Whitespaces werden entfernt
- keine Prefix-Auflösung im Batch
- Duplikate werden vor der Ausführung abgelehnt

### 12.2 Assign

Dry-run zuerst:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_5Y --dry-run
```

Interaktiv:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_5Y
```

Automation:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_5Y --yes
```

### 12.3 Unassign

```bash
bin/cm-retention unassign --file itemtypes.txt --dry-run
bin/cm-retention unassign --file itemtypes.txt
bin/cm-retention unassign --file itemtypes.txt --yes
```

### 12.4 Ablauf des Batch

```text
Phase 1
  für jeden Eintrag normale Java-Dry-run-Validierung
  keine Mutation

Nur wenn ALLE Einträge gültig sind:

Phase 2
  Einträge sequenziell ausführen
  jede einzelne Änderung committen/verifizieren
  beim ersten Fehler sofort stoppen
```

Wichtig: Der Batch ist **nicht atomar**. Beginnt Phase 2 und tritt beim n-ten ItemType ein Fehler auf, bleiben die zuvor erfolgreich bestätigten Änderungen persistent. Spätere ItemTypes werden nicht mehr verarbeitet.

Deshalb:

1. immer erst `--dry-run`,
2. Ausgabe prüfen,
3. bei PROD Zielsystem nochmals mit `status` bestätigen,
4. erst dann echte Ausführung.

Es gibt absichtlich kein `--continue-on-error`.

## 13. Delete

Eine noch verwendete Policy wird nicht gelöscht.

Dry-run:

```bash
bin/cm-retention delete AUTO_DELETE_5Y --dry-run
```

Interaktiv:

```bash
bin/cm-retention delete AUTO_DELETE_5Y
```

Non-TTY:

```bash
bin/cm-retention delete AUTO_DELETE_5Y --yes
```

## 14. TTY vs. Automation

TTY:

- fehlende Einzelargumente können ausgewählt werden
- Prefix-Matching nur in der interaktiven Auswahl
- Write-Bestätigung immer `[y/N]`

Non-TTY:

- vollständige Argumente erforderlich
- exakte Namen
- echter Write benötigt `--yes`
- Dry-run benötigt kein `--yes`

Batch-Dateien verwenden immer exakte ItemType-Namen.

## 15. Exit-Codes

| Code | Bedeutung |
|---:|---|
| `0` | Erfolg / No-op / erfolgreicher Dry-run |
| `2` | CLI-, Konfigurations-, Preflight-, Datei- oder Bestätigungsfehler |
| `3` | IBM-CM-/Runtime-Fehler |
| `4` | ItemType oder Policy nicht gefunden |
| `5` | unsichere/konfliktbehaftete Operation bzw. stale state |
| `6` | Verifikationswarnung/-fehler; gewünschter Zustand kann trotz sekundärem IBM-Fehler bereits persistent sein |

Im Datei-Batch wird beim ersten Fehler gestoppt und dessen Exit-Code zurückgegeben.

## 16. Update / Rollback ohne Git

Empfohlen sind versionsgebundene Verzeichnisse:

```text
/home/ibmcmadm/cm-retention-0.2.0
/home/ibmcmadm/cm-retention-0.2.1
```

Beim Update:

1. neues Runtime-Archiv separat entpacken,
2. `.env` kontrolliert übernehmen,
3. Rechte `0600` prüfen,
4. `doctor` und `status` ausführen,
5. Read-only-Befehle testen,
6. erst danach administrativ nutzen.

Rollback bedeutet dann, wieder das zuvor abgenommene versionsgebundene Runtime-Verzeichnis zu verwenden.

## 17. Wichtige fachliche Hinweise

- Die vom Tool erzeugten Policies sind `FIXED_TIME`-Expiration-Policies mit `AUTO_DELETE`.
- Das CLI löscht selbst keine Dokumente.
- Eine spätere Policy-Zuordnung führt nicht automatisch zu einem Backfill bestehender Dokumente.
- Direkte Änderungen an IBM-CM-Systemtabellen sind kein Bestandteil des Tools.

## 18. Troubleshooting

Siehe:

- `docs/TROUBLESHOOTING.md`
- `docs/METADATA_REPAIR.md`

Das Metadatenproblem ist fachlich unabhängig von der CLI-Entwicklung und wird separat behandelt.
