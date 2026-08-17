# cm-retention – Betriebs- und Benutzerhandbuch

**Version:** 0.1.2  
**Ziel:** IBM Content Manager Enterprise Edition 8.7  
**Laufzeit:** Java 8, lokale IBM-CM-Java-API

## 1. Zweck

`cm-retention` verwaltet Retention-/Expiration-Policies und deren Zuordnung zu IBM-CM-Itemtypes.

Es ist bewusst kein allgemeines CM-Administrationswerkzeug. Der Funktionsumfang ist auf nachvollziehbare, kontrollierbare Retention-Aufgaben begrenzt.

### Unterstützt

- CM-Verbindung testen
- Itemtypes auflisten und Details anzeigen
- bestehende Policies auflisten und anzeigen
- Policy-Verwendung durch Itemtypes anzeigen
- zeitbasierte `AUTO_DELETE`-Policies erzeugen
- unbenutzte Policies löschen
- Policy einem Itemtype zuordnen
- Policy-Zuordnung entfernen
- persistierten Zustand nach fehlerhaftem Itemtype-Update erneut verifizieren

### Nicht unterstützt

- direkte Dokumentlöschung
- unmittelbarer Aufruf von `deleteExpiredItems()`
- automatischer Backfill für bestehende Dokumente
- direkte Änderungen an IBM-CM-Systemtabellen
- Massenänderungen an Itemtypes
- Umgehung der Sicherheitsprüfungen mit `--force`

## 2. Fachliches Modell

### Retention

Retention ist eine Mindestaufbewahrungs-/Schutzfrist. Solange sie aktiv ist, darf ein Objekt nicht gelöscht werden.

Die von `policy create` angelegten Policies verwenden bewusst:

```text
Retention type:    FIXED_TIME
Retention enabled: false
```

### Expiration

Expiration beschreibt, wann ein Objekt abläuft. `cm-retention` erzeugt:

```text
Expiration enabled: true
Expiration action:  AUTO_DELETE
```

`AUTO_DELETE` bedeutet, dass die eigentliche Löschung durch IBM-CM-Hintergrundverarbeitung erfolgt. Das CLI löscht selbst keine Dokumente.

Eine vorhandene Policy mit `NO_ACTION` kann eine Ablaufzeit besitzen, ohne automatisch zu löschen.

### Bestehende Dokumente

Eine spätere Policy-Zuordnung zu einem Itemtype ist **kein Backfill** für bereits vorhandene Dokumente. Ein fachlich gewünschtes rückwirkendes Ablaufdatum muss separat geplant, getestet und freigegeben werden.

## 3. Voraussetzungen

Typische Umgebung:

```text
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Erforderlich:

```text
${IBMCMROOT}/lib/cmbicmsdk81.jar
${JAVA_HOME}/bin/java
${JAVA_HOME}/bin/javac
${JAVA_HOME}/bin/jar
```

Der in `CM_DATABASE` angegebene Library-Server-Alias muss in der auf dem Host verwendeten IBM-CM-Konfiguration vorhanden sein.

## 4. Installation

### Aus GitHub

```bash
git clone https://github.com/mrAibo/CM_retention.git
cd CM_retention
```

### Lokale Konfiguration

```bash
cp .env.example .env
chmod 600 .env
vi .env
```

Beispiel:

```dotenv
CM_DATABASE=LSDB
CM_USER=icmadmin
CM_PASSWORD=CHANGE_ME
IBMCMROOT=/opt/IBM/db2cmv8
JAVA_HOME=/opt/IBM/WebSphere/AppServer/java/8.0
```

Die `.env` wird nicht mit `source` ausgeführt. Java liest die benötigten CM-Werte aus der Datei; der Wrapper verwendet daraus nur die lokalen Installationspfade. Das Passwort erscheint weder als Kommandozeilenargument noch im Git-Repository.

Der Wrapper verweigert eine `.env`, die Rechte für Gruppe oder andere Benutzer besitzt. Empfohlen:

```bash
chmod 600 .env
```

### Build

```bash
./build.sh
```

Erwartet:

```text
Built: .../build/cm-retention.jar
```

### Basistest

```bash
bin/cm-retention version
bin/cm-retention connection test
```

## 5. Mehrere Systeme sicher trennen

Test und Produktion sollten **eigene Konfigurationsdateien** besitzen.

Beispiel:

```bash
install -m 600 .env.example /secure/cm-test.env
install -m 600 .env.example /secure/cm-prod.env
```

Danach jeden Befehl mit dem Zielsystem ausführen:

```bash
bin/cm-retention --env /secure/cm-test.env policy list
bin/cm-retention --env /secure/cm-prod.env policy list
```

Alternativ:

```bash
CM_RETENTION_ENV=/secure/cm-test.env bin/cm-retention itemtype list
```

Damit wird vermieden, dass vor einer produktiven Änderung versehentlich nur der Inhalt einer gemeinsamen `.env` umgeschaltet wird.

## 6. Befehle

### Verbindung

```bash
bin/cm-retention connection test
```

Beispiel:

```text
Connection: OK
Database:   LSDB
User:       icmadmin
CM API:     8.7.0.000
```

### Itemtypes auflisten

```bash
bin/cm-retention itemtype list
```

Ausgabe enthält Itemtype, zugeordnete Policy und Beschreibung.

### Itemtype anzeigen

```bash
bin/cm-retention itemtype show TEST_ITEMTYPE
```

Ausgegeben werden unter anderem:

- Beschreibung
- interne Entity-ID
- Klassifikation
- Version-Control-Einstellungen
- Legacy-Retention-Wert
- zugeordnete Retention-Policy
- Auto-Delete-Scheduler-Einstellungen des Itemtypes

### Policies auflisten

```bash
bin/cm-retention policy list
```

Für jede Policy werden Typ, Expiration, Aktion und Anzahl der zugeordneten Itemtypes angezeigt. Verwendete Policies listen die Itemtype-Namen direkt darunter auf.

### Policy anzeigen

```bash
bin/cm-retention policy show POLICY_NAME
```

Bei Namen mit Leerzeichen:

```bash
bin/cm-retention policy show "Policy Name"
```

### Policy-Verwendung anzeigen

```bash
bin/cm-retention policy usage POLICY_NAME
```

### Policy erzeugen

Minimal:

```bash
bin/cm-retention policy create AUTO_DELETE_1Y \
  --expiration 1y \
  --yes
```

Vollständig:

```bash
bin/cm-retention policy create AUTO_DELETE_1Y \
  --expiration 1y \
  --schedule "0 2 * * *" \
  --commit-count 100 \
  --max-items 5000 \
  --max-duration 120 \
  --yes
```

Erzeugte Policy:

```text
Retention type:             FIXED_TIME
Retention enabled:          false
Expiration enabled:         true
Expiration action:          AUTO_DELETE
Force check-in:             false   # sofern nicht explizit aktiviert
```

#### Alter

Unterstützt:

```text
1y    Jahr(e)
12m   Monat(e)
52w   Woche(n)
365d  Tag(e)
```

Der Wert muss positiv und ganzzahlig sein.

#### Optionen

| Option | Bedeutung | Standard |
|---|---|---:|
| `--expiration` | Ablaufzeit | erforderlich |
| `--schedule` | IBM-CM-Schedule-Information | `0 2 * * *` |
| `--commit-count` | Items pro Commit | `100` |
| `--max-items` | maximale Items pro Lauf, `0` = unbegrenzt | `5000` |
| `--max-duration` | maximale Laufzeit in Minuten | `120` |
| `--force-checkin` | Force Check-in vor Löschen | aus |

`cm-retention` validiert die fachliche Semantik des Schedule-Strings nicht.

### Policy zuordnen

```bash
bin/cm-retention itemtype assign TEST_ITEMTYPE AUTO_DELETE_1Y --yes
```

Danach immer kontrollieren:

```bash
rc=$?
echo "RC=$rc"
bin/cm-retention itemtype show TEST_ITEMTYPE
bin/cm-retention policy usage AUTO_DELETE_1Y
```

### Policy-Zuordnung entfernen

```bash
bin/cm-retention itemtype unassign TEST_ITEMTYPE --yes
```

### Policy löschen

```bash
bin/cm-retention policy delete AUTO_DELETE_1Y --yes
```

Eine noch verwendete Policy wird bewusst abgelehnt. Zuerst müssen alle Zuordnungen entfernt werden.

## 7. Empfohlener Testablauf

### Phase A – nur lesen

```bash
bin/cm-retention connection test
bin/cm-retention itemtype list
bin/cm-retention policy list
```

### Phase B – Policy-Lifecycle ohne Itemtype

```bash
POLICY=ZZ_AUTO_DELETE_1Y_TEST

bin/cm-retention policy create "$POLICY" --expiration 1y --yes || exit $?
bin/cm-retention policy show "$POLICY" || exit $?
bin/cm-retention policy usage "$POLICY" || exit $?
bin/cm-retention policy delete "$POLICY" --yes || exit $?
```

### Phase C – Zuordnung mit eigenem Test-Itemtype

```bash
ITEMTYPE=TEST_ITEMTYPE
POLICY=ZZ_AUTO_DELETE_1Y_TEST

bin/cm-retention policy create "$POLICY" --expiration 1y --yes || exit $?
bin/cm-retention itemtype show "$ITEMTYPE" || exit $?

bin/cm-retention itemtype assign "$ITEMTYPE" "$POLICY" --yes
rc=$?

case "$rc" in
  0)
    echo "Assignment cleanly successful"
    ;;
  6)
    echo "Assignment persisted, but IBM CM returned a secondary error" >&2
    bin/cm-retention itemtype show "$ITEMTYPE"
    exit 6
    ;;
  *)
    echo "Assignment failed: RC=$rc" >&2
    exit "$rc"
    ;;
esac

bin/cm-retention itemtype unassign "$ITEMTYPE" --yes || exit $?
bin/cm-retention policy delete "$POLICY" --yes || exit $?
```

## 8. Produktiver Ablauf

Vor einer produktiven Policy-Zuordnung:

1. explizit die Produktions-`.env` auswählen;
2. Verbindung testen;
3. aktuellen Itemtype-Zustand anzeigen;
4. bestehende Policies prüfen;
5. neue Policy zunächst **ohne** Itemtype-Zuordnung erzeugen;
6. Policy-Details und Schedule fachlich prüfen;
7. bekannte Itemtype-/View-Metadatenprobleme ausschließen;
8. Änderung in einem abgestimmten Wartungsfenster durchführen;
9. Exit-Code auswerten;
10. Zustand über eine neue Verbindung erneut lesen;
11. IBM-CM-Logs auf sekundäre Fehler prüfen;
12. funktionalen Test mit neu erzeugtem Testdokument durchführen.

Bestehende Dokumente sind ein separater Migrationsfall.

## 9. Exit-Codes

| Code | Bedeutung |
|---:|---|
| `0` | erfolgreich oder gewünschter Zustand bereits vorhanden |
| `2` | Argument-/Konfigurationsfehler oder fehlendes `--yes` |
| `3` | IBM CM API / Java / Laufzeitfehler |
| `4` | Itemtype oder Policy nicht gefunden |
| `5` | konfliktbehaftete oder unsichere Schreiboperation |
| `6` | gewünschte Itemtype-Änderung wurde persistent gespeichert, IBM CM meldete danach aber einen sekundären Fehler |

### Warum Exit-Code 6 wichtig ist

Auf älteren CM-Systemen kann eine Itemtype-Änderung zunächst persistiert werden und anschließend beim Aktualisieren zugehöriger Views fehlschlagen. `cm-retention` verbindet sich deshalb nach einem Fehler erneut und prüft die tatsächlich gespeicherte Policy-Zuordnung.

`6` bedeutet daher:

```text
Zielzustand gespeichert: JA
Operation technisch sauber: NEIN
Weitere Diagnose nötig: JA
```

Ein `RC=6` darf in Automatisierung nicht wie `RC=0` behandelt werden.

## 10. Fehlerdiagnose

Ausführliche Diagnose und bekannte Fehlerbilder:

- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Metadaten-Reparatur](docs/METADATA_REPAIR.md)

Stacktrace bei Bedarf:

```bash
CM_DEBUG=true bin/cm-retention itemtype show TEST_ITEMTYPE
```

oder:

```bash
CM_DEBUG=true \
  bin/cm-retention itemtype assign TEST_ITEMTYPE POLICY_NAME --yes
```

## 11. Neustarts

Nach einer normalen Policy-Erstellung, -Löschung oder -Zuordnung ist **kein pauschaler Neustart** von DB2, Library Server, Resource Manager, WebSphere oder IBM Content Navigator vorgesehen.

Empfohlen:

- Administration Client aktualisieren/neu öffnen;
- Test mit einer neuen CM-Verbindung durchführen;
- lang laufende Anwendungen bei nachgewiesenem Metadaten-Cache neu verbinden;
- Serverkomponenten nur bei einem konkret nachgewiesenen Laufzeit-/Cacheproblem neu starten.

## 12. Sicherheit

- `.env` nicht committen;
- `.env` auf `0600` setzen;
- Test und Produktion über getrennte Dateien verwalten;
- produktive Schreibbefehle nicht aus Shell-History mit Passwörtern bauen;
- `--force-checkin` nur nach fachlicher Prüfung verwenden;
- keine direkten SQL-Änderungen an CM-Systemtabellen aus diesem Tool ableiten;
- Backfills bestehender Dokumente als eigenes Change-/Migrationsprojekt behandeln.
