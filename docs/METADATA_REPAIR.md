# IBM CM Component-View-Metadaten kontrolliert reparieren

Dieses Verfahren beschreibt einen **konservativen Reparaturweg** für ältere Itemtype-/Component-View-Definitionen, die beim Aktualisieren mit einem Fehler wie

```text
DGL0303A
DKAttrDefICM::getViewOperator() opCode : [-1]
```

auffallen.

Es basiert auf einem real beobachteten CM-8.7-Metadatenproblem. Es ist kein Ersatz für IBM-Supportvorgaben oder das lokale Change-Verfahren.

## Grundregel

**Nicht** mit einem pauschalen SQL-Update beginnen.

Die internen CM-Systemtabellen bilden zusammenhängende Definitionen ab. Eine scheinbar offensichtliche Einzeländerung kann fachliche Filter, Reihenfolge, ACL- oder View-Eigenschaften beschädigen.

## 1. Test und Produktion getrennt behandeln

Itemtype-, Subset- und Component-View-IDs können zwischen Umgebungen unterschiedlich sein.

Deshalb:

1. TEST separat auditieren;
2. TEST reparieren und vollständig verifizieren;
3. PROD separat neu auditieren;
4. keine IDs aus TEST in PROD übernehmen.

## 2. Betroffene View identifizieren

Read-only:

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

Für jeden Treffer unterscheiden:

```text
FILTERFLAG = 0   kein Filter aktiv
FILTERFLAG <> 0  Filter aktiv oder Metadaten widersprüchlich
```

Bei `FILTERFLAG <> 0` nicht nach dem unten beschriebenen einfachen Neu-Schreibverfahren vorgehen, bevor die fachliche Filterdefinition geklärt ist.

## 3. Vorher sichern

Vor jeder Änderung die betroffene View dokumentieren:

- Itemtype-Name und ID
- Subset-/View-Name
- ComponentViewID
- ACL
- Attribute
- Attributreihenfolge
- repräsentatives Attribut
- Lese-/Schreibrechte
- Filter und Vergleichswerte

Zusätzlich die relevanten Tabellenzeilen exportieren, beispielsweise:

```bash
db2 "EXPORT TO /secure/backup/view_attrs_<VIEWID>.ixf OF IXF
SELECT *
FROM ICMADMIN.ICMSTCOMPVIEWATTRS
WHERE COMPONENTVIEWID = <VIEWID>
WITH UR"
```

```bash
db2 "EXPORT TO /secure/backup/view_defs_<VIEWID>.ixf OF IXF
SELECT *
FROM ICMADMIN.ICMSTCOMPVIEWDEFS
WHERE COMPONENTVIEWID = <VIEWID>
WITH UR"
```

Diese Exporte sind zunächst Beweissicherung/Dokumentation. Nicht blind in CM-Systemtabellen zurückimportieren.

## 4. Pilot nur auf TEST

Einen einzelnen, bereits verstandenen betroffenen Itemtype als Pilot auswählen.

Während der Reparatur möglichst keine parallelen administrativen Änderungen am Itemtype durchführen.

## 5. Subset im IBM System Administration Client öffnen

Sinngemäß:

```text
Data Modeling
  → Item Types
    → <ITEMTYPE>
      → Item Type Subsets
        → <BETROFFENES_SUBSET>
```

Vor dem Speichern nochmals vergleichen:

- ACL
- Attributliste
- Reihenfolge
- repräsentatives Attribut
- Lese-/Schreibrechte
- Filter

## 6. Ungültige ungefilterte Attribute neu schreiben lassen

Nur wenn vorher bestätigt wurde, dass die betroffenen Attribute **keinen Filter** besitzen:

1. betroffenes Attribut aus dem Subset entfernen;
2. dasselbe Attribut wieder hinzufügen;
3. ursprüngliche Reihenfolge wiederherstellen;
4. ursprüngliche Rechte wiederherstellen;
5. repräsentatives Attribut korrekt markieren;
6. keinen neuen Filter setzen;
7. ACL unverändert lassen;
8. Subset speichern.

Ziel ist, dass IBM CM die Definition mit dem aktuellen Administration Client sauber neu erzeugt, statt eine interne Tabelle manuell zu patchen.

## 7. Danach Datenbank prüfen

Die View darf anschließend keine unbekannten Operatorwerte mehr enthalten:

```sql
SELECT
    COMPONENTVIEWID,
    ATTRIBUTEID,
    SEQUENCENUM,
    ATTRIBUTEFLAGS,
    VIEWOPERATOR,
    VIEWCOMPAREVALUE
FROM ICMADMIN.ICMSTCOMPVIEWATTRS
WHERE COMPONENTVIEWID = <VIEWID>
  AND VIEWOPERATOR NOT IN (0, 1, 2, 3, 4, 13, 14)
ORDER BY SEQUENCENUM
WITH UR;
```

Erwartung:

```text
0 rows
```

Danach die vollständige View gegen die Sicherung vergleichen. Nicht nur `VIEWOPERATOR`, sondern auch Reihenfolge, Flags, ACL und fachliche Filter prüfen.

## 8. Access-Module und Logs kontrollieren

Nach dem Speichern prüfen, ob IBM CM die View/Access-Module ohne Folgefehler aktualisiert hat.

Insbesondere nach folgenden Mustern suchen:

```text
DGL0303A
getViewOperator
ICM7022
mkdir error
```

Ein separates Filesystem-/Berechtigungsproblem muss vor weiteren Itemtype-Änderungen behoben sein.

## 9. Fachlicher Test

Der reparierte Subset muss weiterhin wie zuvor funktionieren:

- Suche
- Dokument öffnen
- sichtbare Attribute
- editierbare/nicht editierbare Attribute
- ACL-Zugriff
- repräsentatives Attribut
- eventuell vorhandene Filter

## 10. Retention-Regressionstest

Erst danach den ursprünglichen Admin-Pfad erneut testen:

```bash
bin/cm-retention itemtype assign TEST_ITEMTYPE TEST_POLICY --yes
echo "RC=$?"
```

Erwartung:

```text
RC=0
```

Danach sauber zurückbauen:

```bash
bin/cm-retention itemtype unassign TEST_ITEMTYPE --yes
echo "RC=$?"
```

Auch hier wird `RC=0` erwartet.

## 11. Weitere Itemtypes und Produktion

Erst nach erfolgreichem Pilot:

1. weitere TEST-Views einzeln bearbeiten;
2. jede View vorher sichern und danach prüfen;
3. PROD mit einer **neuen** read-only Abfrage inventarisieren;
4. PROD-Änderungen in einem abgestimmten Change durchführen.

Keine Massenreparatur, solange nicht bewiesen ist, dass alle Treffer dieselbe Ursache und dieselbe fachliche Bedeutung haben.

## 12. Neustart?

Nach einer erfolgreich neu gespeicherten View ist normalerweise kein pauschaler Neustart von DB2, Library Server, Resource Manager, WebSphere oder ICN vorgesehen.

Empfohlen:

- Administration Client refreshen oder neu starten;
- Tests mit einer neuen CM-Verbindung durchführen;
- lang laufende Clients/Anwendungen nur bei nachgewiesenem Cacheproblem neu verbinden;
- Serverkomponenten nicht prophylaktisch neu starten.
