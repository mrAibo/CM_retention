# cm-retention 0.3.2 – Betriebs- und Benutzerdokumentation

## 1. Zweck

`cm-retention` verwaltet Retention-/Expiration-Policies in IBM Content Manager Enterprise Edition 8.7.

Das Werkzeug ist bewusst klein und administrativ gehalten. Es unterstützt:

- Policy- und ItemType-Anzeige
- Erstellen von FIXED_TIME/AUTO_DELETE-Policies
- vollständige Policy-Erstellung direkt aus Properties-Vorlagen
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
cm-retention 0.3.2
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

`auto-delete.force-checkin=true` bedeutet: **Einchecken vor Löschen erzwingen**.

### Mit einer Vorlage direkt eine Policy erzeugen

Dry-run:

```bash
bin/cm-retention create \
  --properties profiles/auto-delete-5y.properties \
  --dry-run
```

Echte Erstellung:

```bash
bin/cm-retention create \
  --properties profiles/auto-delete-5y.properties
```

Automatisiert:

```bash
bin/cm-retention create \
  --properties profiles/auto-delete-5y.properties \
  --yes
```

Es werden keine zusätzlichen Argumente benötigt. Der Name kommt aus `RET_POLICY_NAME`, die Frist aus `expiration.age`.

Eine explizit ausgewählte Datei ohne `RET_POLICY_NAME` wird abgelehnt.

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
  > --properties FILE
  > ret-policy.properties
  > eingebaute Defaults
```

Beispiele:

```bash
# Komplett aus Vorlage
bin/cm-retention create --properties profiles/auto-delete-5y.properties

# Klassische CLI-Variante
bin/cm-retention create AUTO_DELETE_5Y 5y

# Vorlage, aber Name und Frist bewusst per CLI überschreiben
bin/cm-retention create TEMP_POLICY 30d \
  --properties profiles/auto-delete-5y.properties \
  --dry-run
```

Ein einzelner CLI-Override gewinnt ebenfalls gegen die Properties:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y \
  --properties profiles/auto-delete-5y.properties \
  --schedule "0 4 * * *" \
  --max-items 10000
```

Default Force-Checkin deaktivieren:

```bash
bin/cm-retention create AUTO_DELETE_5Y 5y --no-force-checkin
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

Version 0.3.2 erzeugt:

```text
build/cm-retention.jar
build/cm-retention-0.3.2.jar
build/.version
build/ret-policy.properties
build/profiles/auto-delete-1y.properties
build/profiles/auto-delete-5y.properties
build/profiles/auto-delete-10y.properties
build/cm-retention-0.3.2-runtime.tar.gz
build/SHA256SUMS-0.3.2
```

Das Runtime-TAR.GZ ist für Zielserver ohne Git/javac gedacht und enthält auch die Policy-Vorlagen.

## 10. Installation ohne Git

Auf dem Build-Host:

```bash
./build.sh
```

Dann übertragen:

```text
build/cm-retention-0.3.2-runtime.tar.gz
```

Auf dem Zielserver:

```bash
cd /home/ibmcmadm
tar -xzf cm-retention-0.3.2-runtime.tar.gz
cd cm-retention-0.3.2
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
bin/cm-retention itemtypes
bin/cm-retention create --properties profiles/auto-delete-1y.properties --dry-run
```

Im Create-Plan muss erscheinen:

```text
Name         : AUTO_DELETE_1Y
Expiration   : 1 year
Force checkin: yes
```

und als Properties-Quelle die verwendete Vorlage.

## 12. Exit-Codes

| Code | Bedeutung |
|---:|---|
| 0 | Erfolg / No-op / erfolgreicher Dry-run |
| 2 | CLI-/Config-/Properties-/Preflight-Fehler |
| 3 | IBM-CM-/DB2-Laufzeitfehler |
| 4 | ItemType/Policy nicht gefunden |
| 5 | unsichere oder widersprüchliche Operation verweigert |
| 6 | Verifikationswarnung / Partial-Success-Situation |
