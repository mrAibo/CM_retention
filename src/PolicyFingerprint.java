import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;

import java.util.ArrayList;
import java.util.List;

/** Immutable snapshot used to detect a retention-policy change during a write workflow. */
final class PolicyFingerprint {
    final String name;
    final String retentionType;
    final boolean retentionEnabled;
    final int retentionAmount;
    final String retentionUnit;
    final boolean expirationEnabled;
    final int expirationAmount;
    final String expirationUnit;
    final String expirationAction;
    final String schedule;
    final int commitCount;
    final int maxItems;
    final int maxDuration;
    final boolean forceCheckin;

    PolicyFingerprint(String name,
                      String retentionType,
                      boolean retentionEnabled,
                      int retentionAmount,
                      String retentionUnit,
                      boolean expirationEnabled,
                      int expirationAmount,
                      String expirationUnit,
                      String expirationAction,
                      String schedule,
                      int commitCount,
                      int maxItems,
                      int maxDuration,
                      boolean forceCheckin) {
        this.name = safe(name);
        this.retentionType = safe(retentionType);
        this.retentionEnabled = retentionEnabled;
        this.retentionAmount = retentionAmount;
        this.retentionUnit = safe(retentionUnit);
        this.expirationEnabled = expirationEnabled;
        this.expirationAmount = expirationAmount;
        this.expirationUnit = safe(expirationUnit);
        this.expirationAction = safe(expirationAction);
        this.schedule = safe(schedule);
        this.commitCount = commitCount;
        this.maxItems = maxItems;
        this.maxDuration = maxDuration;
        this.forceCheckin = forceCheckin;
    }

    static PolicyFingerprint from(DKRetentionPolicyDefICM policy) {
        boolean retentionEnabled = policy.isRetentionEnabled();
        int retentionAmount = retentionEnabled ? policy.getRetentionTimePeriod() : 0;
        String retentionUnit = retentionEnabled
                ? String.valueOf(policy.getDefaultRetentionTimeUnit()) : "";

        boolean expirationEnabled = policy.isExpirationEnabled();
        int expirationAmount = expirationEnabled ? policy.getExpirationTimePeriod() : 0;
        String expirationUnit = expirationEnabled
                ? String.valueOf(policy.getDefaultExpirationTimeUnit()) : "";
        String expirationAction = expirationEnabled
                ? String.valueOf(policy.getExpirationAction()) : "";

        boolean autoDelete = expirationEnabled && "AUTO_DELETE".equals(expirationAction);
        String schedule = autoDelete ? policy.getDeleteExpiredItemsScheduleInformation() : "";
        int commitCount = autoDelete ? policy.getDeleteExpiredItemsCommitCount() : 0;
        int maxItems = autoDelete ? policy.getDeleteExpiredItemsMaximumRows() : 0;
        int maxDuration = autoDelete ? policy.getDeleteExpiredItemsMaximumDuration() : 0;
        boolean forceCheckin = autoDelete && policy.isDeleteExpiredItemsForceCheckInEnabled();

        return new PolicyFingerprint(
                policy.getName(),
                String.valueOf(policy.getRetentionType()),
                retentionEnabled,
                retentionAmount,
                retentionUnit,
                expirationEnabled,
                expirationAmount,
                expirationUnit,
                expirationAction,
                schedule,
                commitCount,
                maxItems,
                maxDuration,
                forceCheckin);
    }

    void requireSame(PolicyFingerprint actual, String stage, int exitCode) {
        List<String> differences = differences(actual);
        if (!differences.isEmpty()) {
            throw new CliException("Retention policy changed " + stage + ": "
                    + join(differences) + ". Re-run the operation and review the new policy.", exitCode);
        }
    }

    boolean sameAs(PolicyFingerprint actual) {
        return differences(actual).isEmpty();
    }

    List<String> differences(PolicyFingerprint actual) {
        List<String> changed = new ArrayList<String>();
        if (actual == null) {
            changed.add("policy disappeared");
            return changed;
        }
        addIfDifferent(changed, "name", name, actual.name);
        addIfDifferent(changed, "retention.type", retentionType, actual.retentionType);
        addIfDifferent(changed, "retention.enabled", retentionEnabled, actual.retentionEnabled);
        addIfDifferent(changed, "retention.period", retentionAmount, actual.retentionAmount);
        addIfDifferent(changed, "retention.unit", retentionUnit, actual.retentionUnit);
        addIfDifferent(changed, "expiration.enabled", expirationEnabled, actual.expirationEnabled);
        addIfDifferent(changed, "expiration.period", expirationAmount, actual.expirationAmount);
        addIfDifferent(changed, "expiration.unit", expirationUnit, actual.expirationUnit);
        addIfDifferent(changed, "expiration.action", expirationAction, actual.expirationAction);
        addIfDifferent(changed, "auto-delete.schedule", schedule, actual.schedule);
        addIfDifferent(changed, "auto-delete.commit-count", commitCount, actual.commitCount);
        addIfDifferent(changed, "auto-delete.max-items", maxItems, actual.maxItems);
        addIfDifferent(changed, "auto-delete.max-duration", maxDuration, actual.maxDuration);
        addIfDifferent(changed, "auto-delete.force-checkin", forceCheckin, actual.forceCheckin);
        return changed;
    }

    private static void addIfDifferent(List<String> changed, String label, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            changed.add(label + " " + String.valueOf(expected) + " -> " + String.valueOf(actual));
        }
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(value);
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
