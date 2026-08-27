import java.util.ArrayList;
import java.util.List;

/** Immutable identity of the physical root component used by a backfill. */
final class RootFingerprint {
    final int itemTypeId;
    final int componentTypeId;
    final int segmentId;
    final String tableName;

    RootFingerprint(int itemTypeId, int componentTypeId, int segmentId, String tableName) {
        this.itemTypeId = itemTypeId;
        this.componentTypeId = componentTypeId;
        this.segmentId = segmentId;
        this.tableName = tableName == null ? "" : tableName;
    }

    void requireSame(RootFingerprint actual, String stage, int exitCode) {
        List<String> differences = differences(actual);
        if (!differences.isEmpty()) {
            throw new CliException("Backfill root metadata changed " + stage + ": "
                    + join(differences) + ". Re-run the operation and review the new root mapping.", exitCode);
        }
    }

    boolean sameAs(RootFingerprint actual) {
        return differences(actual).isEmpty();
    }

    private List<String> differences(RootFingerprint actual) {
        List<String> changed = new ArrayList<String>();
        if (actual == null) {
            changed.add("root mapping disappeared");
            return changed;
        }
        if (itemTypeId != actual.itemTypeId) changed.add("ItemTypeID " + itemTypeId + " -> " + actual.itemTypeId);
        if (componentTypeId != actual.componentTypeId) changed.add("ComponentTypeID " + componentTypeId + " -> " + actual.componentTypeId);
        if (segmentId != actual.segmentId) changed.add("SegmentID " + segmentId + " -> " + actual.segmentId);
        if (!tableName.equals(actual.tableName)) changed.add("table " + tableName + " -> " + actual.tableName);
        return changed;
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(value);
        }
        return builder.toString();
    }
}
