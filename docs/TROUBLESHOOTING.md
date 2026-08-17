# Troubleshooting

Diese Datei sammelt bekannte Betriebs- und IBM-CM-Fehlerbilder, die beim Einsatz von `cm-retention` auftreten können.

## 1. `.env` wird abgelehnt

### Fehler

```text
ERROR: insecure permissions on .../.env: 644
```

### Ursache

Der Wrapper akzeptiert keine Konfigurationsdatei, die für Gruppe oder andere Benutzer zugänglich ist.

### Lösung

```bash
chmod 600 .env
```

Prüfen:

```bash
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
bin/cm-retention --env /secure/cm-test.env connection test
```

## 3. Falscher oder unbekannter CM-Alias

Wenn `CM_DATABASE` nicht dem auf dem Host konfigurierten IBM-CM-Library-Server-Alias entspricht, schlägt bereits die Verbindung fehl.

Prüfen:

- richtige `.env` ausgewählt?
- korrekter Library-Server-Alias?
- richtige IBM-CM-Clientkonfiguration aktiv?
- richtige Benutzerkennung?

Beginnen mit:

```bash
bin/cm-retention --env /secure/cm-test.env connection test
```

## 4. Exit-Code 6 nach `itemtype assign` oder `unassign`

### Bedeutung

`cm-retention` hat nach einer IBM-CM-Exception die Verbindung beendet, neu aufgebaut und den persistierten Itemtype-Zustand erneut gelesen.

Exit-Code `6` wird nur verwendet, wenn der angeforderte Zustand **tatsächlich gespeichert wurde**, IBM CM danach aber einen weiteren Fehler gemeldet hat.

Nicht einfach wiederholen. Zuerst Zustand und Logs prüfen:

```bash
bin/cm-retention itemtype show ITEMTYPE
bin/cm-retention policy usage POLICY_NAME
```

Mit Diagnose:

```bash
CM_DEBUG=true bin/cm-retention itemtype show ITEMTYPE
```

Bekannte Ursachen aus realen CM-8.7-Umgebungen sind insbesondere:

- fehlender Schreibzugriff für den DB2-Fenced-Prozess auf das Access-Module-Verzeichnis;
- inkonsistente ältere Itemtype-/Component-View-Metadaten.

## 5. `ICM7022`, Reason Code 13, `mkdir error`

### Typisches Fehlerbild

Im Library-Server-Log kann sinngemäß erscheinen:

```text
ICM7022
reasonCode 13
mkdir error
```

### Beobachtete Ursache

Der DB2-Fenced-Benutzer konnte das Verzeichnis für neu zu erzeugende Access-Module nicht beschreiben.

### Prüfung

Zuerst den **tatsächlichen** Pfad und Fenced-Benutzer des jeweiligen Systems ermitteln. Nicht Testwerte blind auf Produktion übertragen.

Beispiel für eine Pfadprüfung:

```bash
namei -l /path/to/cmgmt/ls/LSDB
getfacl -p /path/to/cmgmt /path/to/cmgmt/ls /path/to/cmgmt/ls/LSDB
```

Kontrollierter Schreibtest als root:

```bash
/usr/sbin/runuser -u db2fcm -- /bin/sh -c '
  FILE=/path/to/cmgmt/ls/LSDB/.cm-permission-test-$$
  : > "$FILE" &&
  ls -l "$FILE" &&
  rm -f "$FILE"
'
echo "RC=$?"
```

Erwartet:

```text
RC=0
```

### Reparatur

Nur die für den realen Prozess benötigten Rechte setzen. Kein pauschales `chmod 777`.

Eigentümer, Gruppe und ACL-Modell können zwischen Test und Produktion unterschiedlich sein.

## 6. `DGL0303A` / `getViewOperator() opCode [-1]`

### Typisches Fehlerbild

```text
DGL0303A: Invalid parameter
DKAttrDefICM::getViewOperator() opCode : [-1]
```

### Beobachtete Ursache

Bei älteren Component Views wurden Einträge in `ICMSTCOMPVIEWATTRS` gefunden, deren `VIEWOPERATOR=-1` ist. Bei den untersuchten fehlerhaften Zeilen war gleichzeitig kein Attributfilter aktiv.

Der aktuelle CM-8.7-SDK kann beim Neuaufbau/Aktualisieren der Itemtype-View über diesen alten Metadatenzustand stolpern.

### Nur lesender Audit

```sql
SELECT
    CV.ITEMTYPEID,
    CV.COMPONENTVIEWID,
    RTRIM(CV.COMPONENTVIEWNAME) AS VIEWNAME,
    COUNT(*) AS INVALID_COUNT
FROM ICMADMIN.ICMSTCOMPVIEWDEFS CV
JOIN ICMADMIN.ICMSTCOMPVIEWATTRS VA
  ON VA.COMPONENTVIEWID = CV.COMPONENTVIEWID
WHERE VA.VIEWOPERATOR NOT IN (0, 1, 2, 3, 4, 13, 14)
GROUP BY
    CV.ITEMTYPEID,
    CV.COMPONENTVIEWID,
    CV.COMPONENTVIEWNAME
ORDER BY
    CV.ITEMTYPEID,
    CV.COMPONENTVIEWID
WITH UR;
```

Details:

```sql
SELECT
    CV.ITEMTYPEID,
    CV.COMPONENTVIEWID,
    RTRIM(CV.COMPONENTVIEWNAME) AS VIEWNAME,
    VA.ATTRIBUTEID,
    VA.SEQUENCENUM,
    VA.ATTRIBUTEFLAGS,
    BITAND(VA.ATTRIBUTEFLAGS, 8) AS FILTERFLAG,
    VA.VIEWOPERATOR,
    VA.VIEWCOMPAREVALUE
FROM ICMADMIN.ICMSTCOMPVIEWDEFS CV
JOIN ICMADMIN.ICMSTCOMPVIEWATTRS VA
  ON VA.COMPONENTVIEWID = CV.COMPONENTVIEWID
WHERE VA.VIEWOPERATOR NOT IN (0, 1, 2, 3, 4, 13, 14)
ORDER BY
    CV.ITEMTYPEID,
    CV.COMPONENTVIEWID,
    VA.SEQUENCENUM
WITH UR;
```

### Wichtig

Ein Treffer mit aktivem Filter (`FILTERFLAG <> 0`) darf **nicht** blind auf Operator `0` gesetzt werden. Dann muss die tatsächliche Filterdefinition rekonstruiert werden.

Keine Massenänderung direkt per SQL durchführen. Siehe [METADATA_REPAIR.md](METADATA_REPAIR.md).

## 7. Policy kann nicht gelöscht werden

```text
ERROR: Policy is assigned to ... Unassign it first.
```

Verwendung anzeigen:

```bash
bin/cm-retention policy usage POLICY_NAME
```

Jede Zuordnung gezielt entfernen:

```bash
bin/cm-retention itemtype unassign ITEMTYPE --yes
```

Danach löschen:

```bash
bin/cm-retention policy delete POLICY_NAME --yes
```

## 8. Stacktrace einschalten

```bash
CM_DEBUG=true bin/cm-retention connection test
```

oder:

```bash
CM_DEBUG=true bin/cm-retention itemtype assign ITEMTYPE POLICY --yes
```

`CM_DEBUG=true` ist nur für Diagnose gedacht und kann umfangreiche technische Informationen ausgeben.
