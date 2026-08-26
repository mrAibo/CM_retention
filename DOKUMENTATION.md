# cm-retention 0.3.3 – Betriebs- und Benutzerdokumentation

## 1. Zweck

`cm-retention` verwaltet Retention-/Expiration-Policies in IBM Content Manager Enterprise Edition 8.7.

Das Werkzeug ist bewusst klein und administrativ gehalten. Es unterstützt:

- Policy- und ItemType-Anzeige
- Erstellen von FIXED_TIME/AUTO_DELETE-Policies
- vollständige Policy-Erstellung direkt aus Properties-Vorlagen
- automatische Erkennung einer lesbaren `.properties`-Datei bei `create`
- mehrere wiederverwendbare Vorlagen unter `profiles/`
- Assign/Unassign
- Dry-run
- Datei-Batch mit `--file`
- expliziten Existing-Item-Backfill mit `--backfill`
- Policy-Defaults über `ret-policy.properties`

## 2. Version

```bash
bin/cm-retention version
```

Erwartet:

```text
cm-retention 0.3.3
```

## 3. Policy-Vorlagen

Die Standardvorlage steht in:

```text
ret-policy.properties
```

Jede verwendete Properties-Vorlage muss selbst den Policy-Namen enthalten:

```properties
RET_POLICY_NAME=AUTO_DELETE_1Y
```

Standardvorlage:

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

Wichtig: `auto-delete.max-duration` wird in **Sekunden** angegeben. `120` bedeutet also 120 Sekunden bzw. 2 Minuten, nicht 120 Minuten.

`auto-delete.force-checkin=true` bedeutet: **Einchecken vor Löschen erzwingen**.

### Empfohlene Kurzform

Eine lesbare `.properties`-Datei wird bei `create` automatisch als Template erkannt:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --dry-run
```

Echte Erstellung:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties
```

Automatisiert:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --yes
```

Die Datei darf auch nach normalen Flags stehen:

```bash
bin/cm-retention create --dry-run profiles/auto-delete-5y.properties
```

Die Auto-Erkennung greift nur, wenn die Datei:

- auf `.properties` endet,
- existiert,
- eine reguläre Datei ist,
- lesbar ist.

Intern wird die Kurzform auf die weiterhin unterstützte explizite Variante normalisiert:

```bash
bin/cm-retention create --properties profiles/auto-delete-5y.properties
```

Name und Frist kommen aus `RET_POLICY_NAME` und `expiration.age`.

Eine explizit oder automatisch ausgewählte Datei ohne `RET_POLICY_NAME` wird abgelehnt.

### Mehrdeutigkeit vermeiden

Im automatischen Template-Modus muss die Properties-Datei das einzige Positionsargument von `create` sein.

Gültig:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties \
  --schedule "0 4 * * *"
```

Absichtlich ungültig:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties 10y
```

Wenn Name oder AGE trotz Vorlage überschrieben werden sollen, muss die explizite Form verwendet werden:

```bash
bin/cm-retention create TEMP_POLICY 10y \
  --properties profiles/auto-delete-5y.properties \
  --dry-run
```

### Mitgelieferte Vorlagen

```text
profiles/auto-delete-1y.properties
profiles/auto-delete-5y.properties
profiles/auto-delete-10y.properties
```

Eigene Vorlagen können einfach kopiert und angepasst werden.

### Priorität

```text
CLI POLICY / AGE / Optionen
  > ausgewählte Properties-Vorlage
  > ret-policy.properties
  > eingebaute Defaults
```

Klassische CLI-Variante:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y
```

Ein einzelner CLI-Override gewinnt gegen die Properties:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties \
  --schedule "0 4 * * *" \
  --max-items 10000 \
  --max-duration 180
```

Auch `--max-duration` verwendet Sekunden; `180` entspricht 3 Minuten.

Default Force-Checkin deaktivieren:

```bash
bin/cm-retention create profiles/auto-delete-5y.properties --no-force-checkin
```

## 4. Unterstütztes Policy-Modell

Policy-Erstellung ist absichtlich auf folgendes Modell begrenzt:

```text
FIXED_TIME
Retention disabled
Expiration enabled
AUTO_DELETE
```

Andere semantische Property-Werte und unbekannte Property-Namen werden abgelehnt.

### Policy anzeigen und zugewiesene ItemTypes prüfen

Alle Policies mit einer kompakten Übersicht anzeigen:

```bash
bin/cm-retention policies
```

Die Spalte `ITEMTYPES` zeigt dabei die **Anzahl** der ItemTypes, denen die jeweilige Policy aktuell zugewiesen ist.

Die vollständigen Details einer einzelnen Policy werden mit folgendem Befehl angezeigt:

```bash
bin/cm-retention policy POLICY
```

Beispiel:

```bash
bin/cm-retention policy AUTO_DELETE_5Y
```

Neben Retention-/Expiration-Parametern, Auto-Delete-Schedule, Commit-Count, Maximum Items, Maximum Duration und Force-Check-in zeigt der Befehl auch die **konkreten ItemTypes**, denen diese Policy aktuell zugewiesen ist.

Beispiel für den unteren Teil der Ausgabe:

```text
Assigned itemtypes:         3
  - AM
  - CONTRACT
  - INVOICE
```

Die ItemTypes werden alphabetisch sortiert ausgegeben.

Ist die Policy keinem ItemType zugewiesen, erscheint:

```text
Assigned itemtypes:         0
```

Damit kann vor Änderungen oder vor dem Löschen einer Policy direkt geprüft werden, wo sie verwendet wird.

## 5. Normaler Assign

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --dry-run
bin/cm-retention assign AM AUTO_DELETE_1Y
```

Ohne `--backfill` werden bestehende Objekte nicht verändert.

## 6. Existing-Item-Backfill

Dry-run:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill --dry-run
```

Die korrekte DB2-Formel lautet:

```text
ICM$AUTODELETEDATE = CREATETS + <Policy-Frist>
```

Beispiel:

```text
Formula : ICM$AUTODELETEDATE = CREATETS + 1 YEAR
```

Das zugrunde liegende SQL entspricht:

```sql
UPDATE ICMADMIN.<ROOT_TABLE>
SET ICM$AUTODELETEDATE = CREATETS + 1 YEAR
WHERE ICM$RETENTIONDATE IS NULL
  AND ICM$AUTODELETEDATE IS NULL
  AND CREATETS IS NOT NULL;
```

Wichtig: die physische Erstellungszeit-Spalte heißt `CREATETS`, nicht `ICM$CREATETS`.

Reale Ausführung:

```bash
bin/cm-retention assign AM AUTO_DELETE_1Y --backfill
```

Siehe zusätzlich [docs/BACKFILL.md](docs/BACKFILL.md).

## 7. Batch

```text
# itemtypes.txt
AM
INVOICE
CONTRACT
```

Dry-run:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --dry-run
```

Mit Backfill:

```bash
bin/cm-retention assign --file itemtypes.txt AUTO_DELETE_1Y --backfill --dry-run
```

Vor dem ersten Write werden alle Einträge validiert. Die eigentliche Ausführung ist sequenziell und nicht atomar.

## 8. Sicherheitsmodell

Normaler Write:

```text
resolve -> read -> validate -> plan -> confirm/dry-run -> mutate -> commit -> verify
```

Backfill:

```text
plan/count
 -> confirm
 -> DB2 UPDATE
 -> DB2 COMMIT
 -> DB2 verify
 -> IBM CM assign
 -> reconnect/verify
 -> final DB2 + policy verify
```

Exit-Code `6` kennzeichnet insbesondere Fälle, in denen ein Teil bereits persistiert sein kann, die Abschlussprüfung aber nicht sauber war.

## 9. Build

Auf einem IBM-CM-8.7-Host mit echter `cmbicmsdk81.jar`:

```bash
./build.sh
```

Version 0.3.3 erzeugt automatisch:

```text
build/cm-retention.jar
build/cm-retention-0.3.3.jar
build/.version
build/ret-policy.properties
build/profiles/auto-delete-1y.properties
build/profiles/auto-delete-5y.properties
build/profiles/auto-delete-10y.properties
build/cm-retention-0.3.3-runtime.tar.gz
build/SHA256SUMS-0.3.3
```

Das Runtime-TAR.GZ ist für Zielserver ohne Git/javac gedacht und enthält auch die Policy-Vorlagen.

## 10. Installation ohne Git

Auf dem Build-Host:

```bash
./build.sh
```

Dann übertragen:

```text
build/cm-retention-0.3.3-runtime.tar.gz
```

Auf dem Zielserver:

```bash
cd /home/ibmcmadm
tar -xzf cm-retention-0.3.3-runtime.tar.gz
cd cm-retention-0.3.3
cp .env.example .env
chmod 600 .env
vi .env
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
```

## 11. Empfohlene Abnahme

```bash
bin/cm-retention version
bin/cm-retention doctor
bin/cm-retention status
bin/cm-retention policies
bin/cm-retention policy AUTO_DELETE_1Y
bin/cm-retention itemtypes
bin/cm-retention create profiles/auto-delete-1y.properties --dry-run
```

Bei `policy AUTO_DELETE_1Y` muss zusätzlich zur Policy-Konfiguration auch die aktuelle Verwendung angezeigt werden:

```text
Assigned itemtypes:         <Anzahl>
```

Bei vorhandenen Zuweisungen folgen darunter die Namen der ItemTypes.

Im Create-Plan muss erscheinen:

```text
Name         : AUTO_DELETE_1Y
Expiration   : 1 year
Limits       : 5000 items / 120 sec
Force checkin: yes
```

und als Properties-Quelle die verwendete Vorlage.

Zusätzlich sollte die klassische Syntax unverändert funktionieren:

```bash
bin/cm-retention create TEST_POLICY 30d --dry-run
```

## 12. Exit-Codes

| Code | Bedeutung |
|---:|---|
| 0 | Erfolg / No-op / erfolgreicher Dry-run |
| 2 | CLI-/Config-/Properties-/Preflight-Fehler |
| 3 | IBM-CM-/DB2-Laufzeitfehler |
| 4 | ItemType/Policy nicht gefunden |
| 5 | unsichere oder widersprüchliche Operation verweigert |
| 6 | Verifikationswarnung / Partial-Success-Situation |
