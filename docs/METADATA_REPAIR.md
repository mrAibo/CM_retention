# IBM CM Component-View-Metadaten kontrolliert reparieren

> **Datenbank-Hinweis:** Dieses Dokument basiert auf einem real beobachteten **DB2-basierten** IBM-CM-8.7-System. Die enthaltenen SQL-Beispiele verwenden DB2-Syntax wie `WITH UR` und sind **keine Oracle-Reparaturanleitung**. Die DB2-/Oracle-Unterstützung von `cm-retention --backfill` in Version 0.4.0 ändert daran nichts: Component-View-Metadatenreparatur ist ein separater, besonders vorsichtiger Diagnose-/Repair-Fall.

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

Read-only, **DB2-Syntax**:

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

```sql
SELECT *
FROM ICMADMIN.ICMSTCOMPVIEWDEFS
WHERE COMPONENTVIEWID = <ID>
WITH UR;

SELECT *
FROM ICMADMIN.ICMSTCOMPVIEWATTRS
WHERE COMPONENTVIEWID = <ID>
ORDER BY SEQUENCENUM
WITH UR;
```

## 4. Nicht blind per SQL reparieren

Der primäre Reparaturweg soll über unterstützte IBM-CM-Administrations-/SDK-Funktionen erfolgen, soweit dies für den konkreten View-Zustand möglich ist. Ein direktes SQL-UPDATE interner Definitionstabellen kann Folgeinkonsistenzen erzeugen.

Wenn eine direkte Datenbankänderung nach IBM-Support-/Change-Freigabe trotzdem erforderlich ist, müssen mindestens:

- exakte betroffene Zeilen gesichert sein,
- die Semantik des Filters verstanden sein,
- TEST und PROD separat verifiziert werden,
- ein Rollback-Weg dokumentiert sein.

## 5. `VIEWOPERATOR=-1` einordnen

Ein beobachteter problematischer Zustand war:

```text
VIEWOPERATOR = -1
FILTERFLAG   = 0
```

Das ist **nicht** gleichbedeutend mit der allgemeinen Regel „-1 auf 0 setzen“. Besonders bei aktivem Filter (`FILTERFLAG <> 0`) kann der korrekte Operator fachlich ein anderer sein.

Deshalb:

```text
FILTERFLAG = 0
  -> möglicher Legacy-/Default-Metadatenfall, weiter untersuchen

FILTERFLAG <> 0
  -> Filtersemantik rekonstruieren; keine pauschale Korrektur
```

## 6. Nach jeder Reparatur verifizieren

Mindestens:

1. denselben Read-only Audit erneut ausführen;
2. ItemType über IBM CM SDK lesen;
3. betroffene View/Subset öffnen;
4. Policy assign/unassign zunächst mit `--dry-run` prüfen;
5. danach echten Assign/Unassign und Persistenzverifikation durchführen;
6. ICMSERVER.log prüfen.

Beispiel:

```bash
bin/cm-retention itemtype ITEMTYPE
bin/cm-retention assign ITEMTYPE POLICY --dry-run
```

## 7. Oracle-Systeme

Auf Oracle-basierten CM-Systemen zuerst die tatsächlich unterstützte Oracle-Syntax, Transaktions-/Read-Consistency-Semantik und das konkrete CM-Metadatenproblem separat verifizieren. Insbesondere `WITH UR` ist DB2-spezifisch.

Dieses Dokument soll nicht durch bloßes Entfernen von `WITH UR` in eine Oracle-Reparaturanleitung umgedeutet werden. Bei internen CM-Systemtabellen ist eine falsche „Portierung“ gefährlicher als ein fehlendes Rezept.

## 8. Bezug zu `cm-retention --backfill`

Die DB2-/Oracle-Unterstützung des Backfills ist davon getrennt. `--backfill` schreibt ausschließlich die für den vorgesehenen Existing-Item-Workflow relevanten Root-Zeilen (`ICM$AUTODELETEDATE`) unter den dokumentierten NULL-/Fingerprint-Guards. Es repariert keine Component-View-Metadaten.
