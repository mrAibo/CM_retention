# cm-retention 0.3.0 – Betriebs- und Benutzerdokumentation

## 1. Zweck

`cm-retention` verwaltet Retention-/Expiration-Policies in IBM Content Manager Enterprise Edition 8.7.

Die normale Policy-Administration erfolgt über die IBM-CM-Java-API. Zusätzlich gibt es seit 0.3.0 einen **expliziten, opt-in bestehenden-Objekt-Backfill** über DB2, der ausschließlich mit `assign ... --backfill` aktiviert wird.

Das Werkzeug bleibt bewusst klein und admin-orientiert: kurze Befehle, Dry-run, interaktive Bestätigung, Status/Doctor, Datei-Batch und klare Exit-Codes.

## 2. Bewusste Grenzen

Nicht automatisch durchgeführt werden:

- direkte Dokumentlöschung
- manueller Aufruf von `deleteExpiredItems()`
- impliziter Backfill bei normalem `assign`
- Überschreiben bereits gesetzter Retention-/Auto-Delete-Daten
- Event-Driven-Konvertierungen
- frei eingebbares SQL oder frei eingebbare Tabellennamen
- stilles Bulk-Verhalten ohne Vorprüfung

Ein normaler Befehl:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y
```

ändert weiterhin **keine** bestehenden Objekte.

Nur:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

aktiviert den bestehenden-Objekt-Backfill.

## 3. Sicherheitsmodell

Normale einzelne Write-Operationen folgen:

```text
1. Ziel auflösen
2. aktuellen Zustand lesen
3. Eingaben/Voraussetzungen validieren
4. Änderungsplan anzeigen
5. Dry-run / Bestätigung behandeln
6. IBM-CM-Änderung ausführen
7. commit
8. Verbindung/Zustand erneut prüfen
9. Ergebnis/Exit-Code melden
```

Assign/Unassign behalten die besondere Persistenzprüfung: meldet IBM CM nach einem ItemType-Update einen Fehler, wird die Verbindung neu aufgebaut und der tatsächlich persistierte Zustand erneut gelesen.

Wenn der gewünschte Zustand bereits gespeichert wurde, IBM CM danach aber noch einen sekundären Fehler meldet, wird **Exit 6** verwendet.

## 4. Backfill-Sicherheitsmodell

`--backfill` ist ein eigener zweistufiger Ablauf:

```text
1. ItemType + Policy über IBM CM auflösen
2. Policy-Typ validieren
3. aktuellen Policy-Zustand des ItemTypes prüfen
4. Root-Component/-Tabelle automatisch ermitteln
5. DB2 Counts / sofort fällige Zeilen ermitteln
6. Dry-run bzw. explizite Bestätigung
7. DB2 UPDATE
8. DB2 COMMIT
9. verbleibende NULL-Zeilen prüfen
10. erst danach Policy über IBM CM zuweisen
11. IBM-CM-Persistenz erneut verifizieren
12. abschließend Policy + DB2-NULL-Zustand erneut prüfen
```

DB2-Backfill und IBM-CM-Policy-Assignment sind **keine gemeinsame Distributed Transaction**. Falls der DB2-Commit bereits erfolgt ist und die spätere Zuweisung/Endprüfung nicht sauber endet, wird der Zustand als teilweise/unsicher behandelt und **Exit 6** verwendet.

## 5. Voraussetzungen

Typische Installation:

```text
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Benötigt für normale Laufzeit:

```text
${IBMCMROOT}/lib/cmbicmsdk81.jar
${IBMCMROOT}/cmgmt/
${IBMCMROOT}/lib/
${JAVA_HOME}/bin/java
```

Nur zum Build aus Source zusätzlich:

```text
${JAVA_HOME}/bin/javac
${JAVA_HOME}/bin/jar
```

Für `--backfill` wird außerdem der DB2-JCC-Treiber (`db2jcc4.jar`) benötigt.

## 6. Konfiguration

Minimale `.env`:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=CHANGE_ME
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Datei absichern:

```bash
chmod 600 .env
```

Die `.env` wird nicht als Shell-Skript ausgeführt. Passwörter erscheinen nicht in CLI-Argumenten.

### 6.1 DB2-Konfiguration für `--backfill`

Optional:

```dotenv
DB2_DATABASE=LSDB
DB2_JDBC_URL=jdbc:db2:LSDB
DB2_USER=icmadmin
DB2_PASSWORD=CHANGE_ME
DB2_SCHEMA=ICMADMIN
DB2_JDBC_JAR=/opt/IBM/db2/V11.5/java/db2jcc4.jar
```

Defaults:

```text
DB2_DATABASE -> CM_DATABASE
DB2_JDBC_URL -> jdbc:db2:<DB2_DATABASE>
DB2_USER     -> CM_USER
DB2_PASSWORD -> CM_PASSWORD
DB2_SCHEMA   -> ICMADMIN
```

Wenn der CM-Alias nicht als DB2-Alias nutzbar ist, `DB2_JDBC_URL` explizit setzen, z. B.:

```dotenv
DB2_JDBC_URL=jdbc:db2://dbhost.example:50000/LSDB
```

Der DB2-Benutzer benötigt SELECT-Rechte auf die relevanten CM-Metadaten-/Root-Tabellen und UPDATE-Rechte auf die Ziel-Root-Tabelle.

## 7. TEST und PROD trennen

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

Vor jeder produktiven Änderung zuerst mit exakt derselben `--env`-Datei `status` ausführen.

## 8. Build

```bash
./build.sh
```

Für 0.3.0 entstehen:

```text
build/cm-retention.jar
build/cm-retention-0.3.0.jar
build/.version
build/cm-retention-0.3.0-runtime.tar.gz
build/SHA256SUMS-0.3.0
```

`cm-retention-0.3.0-runtime.tar.gz` ist das bevorzugte Paket für Zielserver ohne Git und ohne `javac`.

## 9. Erste Abnahme

```bash
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
bin/cm-retention policies
bin/cm-retention itemtypes
```

Erst danach Write-Operationen testen.

## 10. Read-only-Befehle

```bash
bin/cm-retention status
bin/cm-retention doctor
bin/cm-retention policies
bin/cm-retention policy AUTO_DELETE_1Y
bin/cm-retention itemtypes
bin/cm-retention itemtype AM
```

## 11. Policy erstellen

Normal:

```bash
bin/cm-retention create AUTO_DELETE_1Y 1y
```

Defaults:

```text
Retention type    FIXED_TIME
Retention enabled false
Expiration        enabled
Action            AUTO_DELETE
Schedule          0 2 * * *
Commit count      100
Max items         5000
Max duration      120 Minuten
Force check-in    false
```

Dry-run:

```bash
bin/cm-retention create AUTO_DELETE_1Y 1y --dry-run
```

## 12. Normale Policy-Zuweisung

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --dry-run
bin/cm-retention assign AM AUTO_DELETE_1Y
```

Diese Variante backfillt bestehende Objekte **nicht**.

Automation:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --yes
```

## 13. Existing-item Backfill

Ziel: bei bestehenden Root-Zeilen, bei denen beide Felder NULL sind, das Auto-Delete-Datum aus dem Erstellungszeitpunkt plus echter Policy-Frist berechnen.

Dry-run:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

Der Plan zeigt u. a.:

```text
ItemType ID
Root component ID
Root table
Policy-Frist
Formel
Root rows total
Missing both dates
Backfillable rows
NULL create timestamp
Immediately expired after
Already auto-delete dated
Retention date already set
```

Die äquivalente SQL-Logik lautet:

```sql
UPDATE ICMADMIN.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = ICM$CREATETS + <POLICY_EXPIRATION>
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND ICM$CREATETS IS NOT NULL;
```

Die Frist wird aus der Policy gelesen (`YEAR`, `MONTH`, `WEEK`, `DAY`) und nicht fest verdrahtet.

### 13.1 Zulässige Policies

Backfill akzeptiert nur:

```text
Retention type     FIXED_TIME
Retention enabled  false
Expiration enabled true
Expiration action  AUTO_DELETE
Expiration period  > 0
```

### 13.2 Abbruchbedingungen

Backfill wird verweigert bei:

- anderer bereits zugewiesener Policy
- retention-enabled Policy
- Event-Driven Policy
- non-AUTO_DELETE Action
- ungültiger/unsupported Time Unit
- NULL `ICM$CREATETS` bei einer zu backfillenden Zeile
- nicht eindeutig ermittelbarer Root-Component
- fehlendem DB2-Treiber/Zugriff

### 13.3 Root-Tabelle

Der Benutzer gibt keinen Tabellennamen an.

Die Anwendung ermittelt über:

```text
ICMSTCOMPDEFS
ICMSTITEMTYPEDEFS
```

für die IBM-CM-ItemType-ID den Root-Component (`PARENTCOMPTYPEID=0`) und daraus `ICMUT...` inklusive Segment-ID.

### 13.4 Sofort fällige Objekte

Der Dry-run zählt explizit Zeilen, deren berechnetes Auto-Delete-Datum bereits in der Vergangenheit liegt.

Beispiel:

```text
Erstellt: 2022
Policy:   +1 YEAR
Ergebnis: Auto-Delete-Datum 2023
```

Diese Zeilen werden nach Policy-Zuweisung unmittelbar für den AUTO_DELETE-Prozess fällig. Dieser Count muss vor PROD-Ausführung geprüft werden.

### 13.5 Echte Ausführung

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

oder non-interactive:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --yes
```

Wenn dieselbe Policy bereits zugewiesen ist, darf `--backfill` als Recovery für verbliebene NULL-Zeilen verwendet werden.

Wenn eine **andere** Policy zugewiesen ist, wird abgebrochen, damit nicht unbemerkt gemischte Datumslogik entsteht.

## 14. Datei-Batch

Beispiel `itemtypes.txt`:

```text
# Kommentar
AM
INVOICE
CONTRACT
```

Ohne Backfill:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y
```

Mit Backfill:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill
```

Automation:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --yes
```

Phase 1 prüft **alle** Einträge ohne Mutation. Erst wenn alle grün sind, beginnt Phase 2.

Phase 2 ist sequenziell und nicht atomar. Mit Backfill gilt pro ItemType:

```text
DB2 backfill -> verify -> policy assignment -> final verify
```

Bei Fehler stoppt der Batch. Frühere erfolgreiche ItemTypes bleiben committed.

## 15. Unassign

```bash
bin/cm-retention unassign AM --dry-run
bin/cm-retention unassign AM
```

Batch:

```bash
bin/cm-retention unassign --file itemtypes.txt --dry-run
bin/cm-retention unassign --file itemtypes.txt
```

`--backfill` ist für `unassign` nicht zulässig.

## 16. Delete

Eine verwendete Policy wird nicht gelöscht.

```bash
bin/cm-retention delete AUTO_DELETE_1Y --dry-run
bin/cm-retention delete AUTO_DELETE_1Y
```

## 17. Exit-Codes

| Code | Bedeutung |
|---:|---|
| `0` | Erfolg / No change / erfolgreicher Dry-run |
| `2` | CLI-, Konfigurations-, Preflight- oder Confirmation-Fehler |
| `3` | IBM-CM-, DB2- oder Runtime-Fehler |
| `4` | ItemType/Policy nicht gefunden |
| `5` | unsichere/widersprüchliche Operation verweigert |
| `6` | Verifikations-/Partial-Success-Zustand; Änderung kann bereits ganz oder teilweise persistiert sein |

## 18. Recovery bei Exit 6 nach Backfill

1. Keine weitere Bulk-Änderung blind starten.
2. ItemType/Policy prüfen:

```bash
bin/cm-retention itemtype AM
bin/cm-retention policy AUTO_DELETE_1Y
```

3. denselben Backfill erneut als Dry-run ausführen:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

4. Wenn DB2-Backfill bereits vollständig committed ist, sollten keine backfillbaren NULL-Zeilen mehr erscheinen.
5. IBM-CM-Logs bei SDK-Fehlern prüfen.
6. Ursache beheben und denselben idempotenten Backfill-Befehl erneut ausführen.

## 19. Installation ohne Git

Auf kompatiblem Build-Host:

```bash
./build.sh
```

Paket übertragen:

```text
build/cm-retention-0.3.0-runtime.tar.gz
```

Auf Zielhost:

```bash
tar -xzf cm-retention-0.3.0-runtime.tar.gz
cd cm-retention-0.3.0
cp .env.example .env
chmod 600 .env
vi .env
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
```

Git und `javac` sind auf dem Zielhost nicht erforderlich.

## 20. Weitere Dokumente

- `README.md` – Überblick und Installation
- `docs/BACKFILL.md` – detaillierter Backfill-Ablauf
- `docs/TROUBLESHOOTING.md` – Fehlerdiagnose
- `docs/METADATA_REPAIR.md` – separates IBM-CM-Metadaten-Thema
- `CHANGELOG.md` – Versionshistorie
