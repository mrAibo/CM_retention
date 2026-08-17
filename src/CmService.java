import com.ibm.mm.sdk.common.DKConstant;
import com.ibm.mm.sdk.common.DKConstantICM;
import com.ibm.mm.sdk.common.DKDatastoreAdminICM;
import com.ibm.mm.sdk.common.DKDatastoreDefICM;
import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKItemTypeDefICM;
import com.ibm.mm.sdk.common.DKPolicyMgmtICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_EXPIRATION_ACTION_TYPE;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_RETENTION_TYPE;
import com.ibm.mm.sdk.common.DKNVPair;
import com.ibm.mm.sdk.common.dkCollection;
import com.ibm.mm.sdk.common.dkIterator;
import com.ibm.mm.sdk.server.DKDatastoreICM;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class CmService {
    private final Config config;
    private DKDatastoreICM datastore;

    CmService(Config config) {
        this.config = config;
    }

    DKDatastoreICM datastore() throws Exception {
        if (datastore == null || !datastore.isConnected()) {
            datastore = new DKDatastoreICM();
            datastore.connect(config.database, config.user, config.password, "");
        }
        return datastore;
    }

    private DKDatastoreDefICM datastoreDef() throws Exception {
        return (DKDatastoreDefICM) datastore().datastoreDef();
    }

    private DKPolicyMgmtICM policyManager() throws Exception {
        DKDatastoreAdminICM admin = (DKDatastoreAdminICM) datastoreDef().datastoreAdmin();
        return admin.policyMgmt();
    }

    void connectionTest() throws Exception {
        DKDatastoreICM ds = datastore();
        System.out.println("Connection: OK");
        System.out.println("Database:   " + ds.datastoreName());
        System.out.println("User:       " + ds.userName());
        System.out.println("CM API:     " + DKConstantICM.DK_ICM_RELEASE_VERSION);
    }

    int policyCount() throws Exception {
        return policyNames().size();
    }

    int itemTypeCount() throws Exception {
        return listItemTypes().size();
    }

    List<DKItemTypeDefICM> listItemTypes() throws Exception {
        DKNVPair[] options = new DKNVPair[] {
                new DKNVPair(DKConstantICM.DK_ICM_ENTITY_TYPE, Integer.valueOf(DKConstantICM.DK_ICM_BASE)),
                new DKNVPair(DKConstant.DK_CM_PARM_END, null)
        };
        dkCollection collection = datastoreDef().listEntities(options);
        dkIterator iterator = collection.createIterator();
        List<DKItemTypeDefICM> result = new ArrayList<DKItemTypeDefICM>();
        while (iterator.more()) {
            result.add((DKItemTypeDefICM) iterator.next());
        }
        Collections.sort(result, new Comparator<DKItemTypeDefICM>() {
            @Override
            public int compare(DKItemTypeDefICM left, DKItemTypeDefICM right) {
                return safe(left.getName()).compareToIgnoreCase(safe(right.getName()));
            }
        });
        return result;
    }

    List<String> itemTypeNames() throws Exception {
        List<String> names = new ArrayList<String>();
        for (DKItemTypeDefICM itemType : listItemTypes()) {
            names.add(itemType.getName());
        }
        return names;
    }

    DKItemTypeDefICM requireItemType(String name) throws Exception {
        DKItemTypeDefICM itemType = (DKItemTypeDefICM) datastoreDef().retrieveEntity(name);
        if (itemType == null) {
            throw new CliException("Itemtype not found: " + name, 4);
        }
        return itemType;
    }

    void printItemTypeList() throws Exception {
        List<DKItemTypeDefICM> itemTypes = listItemTypes();
        System.out.printf("%-32s %-32s %s%n", "ITEMTYPE", "POLICY", "DESCRIPTION");
        System.out.printf("%-32s %-32s %s%n", repeat('-', 32), repeat('-', 32), repeat('-', 40));
        for (DKItemTypeDefICM itemType : itemTypes) {
            System.out.printf("%-32s %-32s %s%n",
                    safe(itemType.getName()),
                    emptyAsDash(itemType.getItemTypeRetentionPolicyName()),
                    oneLine(itemType.getDescription()));
        }
        System.out.println();
        System.out.println("Count: " + itemTypes.size());
    }

    void printItemType(String name) throws Exception {
        DKItemTypeDefICM itemType = requireItemType(name);
        System.out.println("Itemtype:                   " + itemType.getName());
        System.out.println("Description:                " + safe(itemType.getDescription()));
        System.out.println("Entity ID:                  " + itemType.getIntId());
        System.out.println("Classification:             " + classification(itemType.getClassification()));
        System.out.println("XDO class:                  " + itemType.getXDOClassID() + " / " + safe(itemType.getXDOClassName()));
        System.out.println("Default RM code:            " + itemType.getDefaultRMCode());
        System.out.println("Default collection code:    " + itemType.getDefaultCollCode());
        System.out.println("Version control:            " + versionControl(itemType.getVersionControl()));
        System.out.println("Versioning type:            " + versioningType(itemType.getVersioningType()));
        System.out.println("Legacy default retention:   " + legacyRetention(itemType));
        System.out.println("Retention policy:           " + emptyAsDash(itemType.getItemTypeRetentionPolicyName()));
        System.out.println("Auto-delete scheduler:      " + safe(String.valueOf(itemType.getDeleteExpiredItemsSchedulerType())));
        System.out.println("Auto-delete schedule:       " + emptyAsDash(itemType.getDeleteExpiredItemsScheduleInformation()));
        System.out.println("Auto-delete commit count:   " + itemType.getDeleteExpiredItemsCommitCount());
        System.out.println("Auto-delete max. items:     " + itemType.getDeleteExpiredItemsMaximumRows());
        System.out.println("Auto-delete max. duration:  " + itemType.getDeleteExpiredItemsMaximumDuration());
    }

    List<String> policyNames() throws Exception {
        String[] names = policyManager().listRetentionPolicyNames();
        List<String> sorted = new ArrayList<String>();
        if (names != null) {
            sorted.addAll(Arrays.asList(names));
        }
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    DKRetentionPolicyDefICM requirePolicy(String name) throws Exception {
        for (String existing : policyNames()) {
            if (name.equals(existing)) {
                return policyManager().retrieveRetentionPolicy(name);
            }
        }
        throw new CliException("Policy not found: " + name, 4);
    }

    boolean policyExists(String name) throws Exception {
        for (String existing : policyNames()) {
            if (name.equals(existing)) {
                return true;
            }
        }
        return false;
    }

    List<String> policyUsage(String name) throws Exception {
        String[] itemTypes = policyManager().listItemTypeNamesByRetentionPolicy(name);
        List<String> sorted = new ArrayList<String>();
        if (itemTypes != null) {
            sorted.addAll(Arrays.asList(itemTypes));
        }
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    void printPolicyList() throws Exception {
        List<String> sorted = policyNames();
        System.out.printf("%-32s %-12s %-12s %-16s %s%n",
                "POLICY", "TYPE", "EXPIRATION", "ACTION", "ITEMTYPES");
        System.out.printf("%-32s %-12s %-12s %-16s %s%n",
                repeat('-', 32), repeat('-', 12), repeat('-', 12), repeat('-', 16), repeat('-', 9));
        for (String name : sorted) {
            DKRetentionPolicyDefICM policy = policyManager().retrieveRetentionPolicy(name);
            int assignedCount = policyUsage(name).size();
            System.out.printf("%-32s %-12s %-12s %-16s %d%n",
                    safe(policy.getName()),
                    safe(String.valueOf(policy.getRetentionType())),
                    policy.isExpirationEnabled()
                            ? policy.getExpirationTimePeriod() + unitSuffix(policy.getDefaultExpirationTimeUnit())
                            : "disabled",
                    policy.isExpirationEnabled() ? safe(String.valueOf(policy.getExpirationAction())) : "-",
                    assignedCount);
        }
        System.out.println();
        System.out.println("Count: " + sorted.size());
    }

    void printPolicy(String name) throws Exception {
        DKRetentionPolicyDefICM policy = requirePolicy(name);
        System.out.println("Policy:                     " + policy.getName());
        System.out.println("Retention type:             " + policy.getRetentionType());
        System.out.println("Retention enabled:          " + policy.isRetentionEnabled());
        if (policy.isRetentionEnabled()) {
            System.out.println("Retention period:           " + policy.getRetentionTimePeriod()
                    + " " + policy.getDefaultRetentionTimeUnit());
        }
        System.out.println("Expiration enabled:         " + policy.isExpirationEnabled());
        if (policy.isExpirationEnabled()) {
            System.out.println("Expiration period:          " + policy.getExpirationTimePeriod()
                    + " " + policy.getDefaultExpirationTimeUnit());
            System.out.println("Expiration action:          " + policy.getExpirationAction());
        }
        if (policy.isExpirationEnabled()
                && policy.getExpirationAction() == DK_ICM_EXPIRATION_ACTION_TYPE.AUTO_DELETE) {
            System.out.println("Auto-delete schedule:       " + emptyAsDash(policy.getDeleteExpiredItemsScheduleInformation()));
            System.out.println("Auto-delete commit count:   " + policy.getDeleteExpiredItemsCommitCount());
            System.out.println("Auto-delete max. items:     " + policy.getDeleteExpiredItemsMaximumRows());
            System.out.println("Auto-delete max. duration:  " + policy.getDeleteExpiredItemsMaximumDuration());
            System.out.println("Force check-in:             " + policy.isDeleteExpiredItemsForceCheckInEnabled());
        }
        List<String> usage = policyUsage(name);
        System.out.println("Assigned itemtypes:         " + usage.size());
        for (String itemType : usage) {
            System.out.println("  - " + itemType);
        }
    }

    void assignPolicy(String itemTypeName, String policyName, String expectedCurrent) throws Exception {
        DKItemTypeDefICM itemType = requireItemType(itemTypeName);
        requirePolicy(policyName);
        String current = normalizePolicy(itemType.getItemTypeRetentionPolicyName());
        if (!samePolicy(current, expectedCurrent)) {
            throw new CliException("State changed before update: " + itemTypeName
                    + " now uses " + emptyAsDash(current) + ". Re-run the command.", 5);
        }
        if (policyName.equals(current)) {
            return;
        }

        itemType.setItemTypeRetentionPolicyName(policyName);
        try {
            itemType.update();
            datastore().commit();
        } catch (DKException e) {
            String persisted = readPersistedPolicyName(itemTypeName, e);
            if (policyName.equals(persisted)) {
                throw new OperationWarning(
                        "Itemtype " + itemTypeName + " now uses policy " + policyName
                                + ", but IBM CM reported a secondary error after persisting the change."
                                + " Check ICMSERVER.log before treating the operation as fully successful.", e);
            }
            throw e;
        }

        String persisted = readPersistedPolicyName(itemTypeName, null);
        if (!policyName.equals(persisted)) {
            throw new CliException("Verification failed: itemtype " + itemTypeName
                    + " uses " + emptyAsDash(persisted) + " instead of " + policyName, 6);
        }
    }

    void unassignPolicy(String itemTypeName, String expectedCurrent) throws Exception {
        DKItemTypeDefICM itemType = requireItemType(itemTypeName);
        String current = normalizePolicy(itemType.getItemTypeRetentionPolicyName());
        if (!samePolicy(current, expectedCurrent)) {
            throw new CliException("State changed before update: " + itemTypeName
                    + " now uses " + emptyAsDash(current) + ". Re-run the command.", 5);
        }
        if (current == null) {
            return;
        }

        itemType.setItemTypeRetentionPolicyName("");
        try {
            itemType.update();
            datastore().commit();
        } catch (DKException e) {
            String persisted = readPersistedPolicyName(itemTypeName, e);
            if (persisted == null) {
                throw new OperationWarning(
                        "Policy " + current + " was removed from itemtype " + itemTypeName
                                + ", but IBM CM reported a secondary error after persisting the change."
                                + " Check ICMSERVER.log before treating the operation as fully successful.", e);
            }
            throw e;
        }

        String persisted = readPersistedPolicyName(itemTypeName, null);
        if (persisted != null) {
            throw new CliException("Verification failed: itemtype " + itemTypeName
                    + " still uses policy " + persisted, 6);
        }
    }

    void createPolicy(String name, PolicySettings settings) throws Exception {
        if (policyExists(name)) {
            throw new CliException("Policy already exists: " + name, 5);
        }
        DKRetentionPolicyDefICM policy = new DKRetentionPolicyDefICM();
        policy.setName(name);
        policy.setRetentionType(DK_ICM_RETENTION_TYPE.FIXED_TIME);
        policy.setRetentionEnabled(false);
        policy.setExpirationEnabled(true);
        policy.setExpirationTimePeriod(settings.age.amount);
        policy.setDefaultExpirationTimeUnit(settings.age.unit);
        policy.setExpirationAction(DK_ICM_EXPIRATION_ACTION_TYPE.AUTO_DELETE);
        policy.setDeleteExpiredItemsForceCheckInEnabled(settings.forceCheckin);
        policy.setDeleteExpiredItemsMaximumDuration(settings.maxDuration);
        policy.setDeleteExpiredItemsMaximumRows(settings.maxItems);
        policy.setDeleteExpiredItemsCommitCount(settings.commitCount);
        policy.setDeleteExpiredItemsScheduleInformation(settings.schedule);

        policyManager().add(policy);
        datastore().commit();
        policyManager().clearCache();
        verifyCreatedPolicy(name, settings);
    }

    private void verifyCreatedPolicy(String name, PolicySettings expected) throws Exception {
        DKRetentionPolicyDefICM actual = requirePolicy(name);
        boolean valid = actual.isExpirationEnabled()
                && actual.getExpirationAction() == DK_ICM_EXPIRATION_ACTION_TYPE.AUTO_DELETE
                && actual.getExpirationTimePeriod() == expected.age.amount
                && actual.getDefaultExpirationTimeUnit() == expected.age.unit
                && expected.schedule.equals(actual.getDeleteExpiredItemsScheduleInformation())
                && actual.getDeleteExpiredItemsCommitCount() == expected.commitCount
                && actual.getDeleteExpiredItemsMaximumRows() == expected.maxItems
                && actual.getDeleteExpiredItemsMaximumDuration() == expected.maxDuration
                && actual.isDeleteExpiredItemsForceCheckInEnabled() == expected.forceCheckin;
        if (!valid) {
            throw new CliException("Verification failed after creating policy " + name, 6);
        }
    }

    void deletePolicy(String name) throws Exception {
        requirePolicy(name);
        List<String> usage = policyUsage(name);
        if (!usage.isEmpty()) {
            throw new CliException("Policy became assigned before delete: " + join(usage, ", ")
                    + ". Re-run after unassigning it.", 5);
        }
        policyManager().del(name);
        datastore().commit();
        policyManager().clearCache();
        if (policyExists(name)) {
            throw new CliException("Verification failed: policy still exists after delete: " + name, 6);
        }
    }

    private String readPersistedPolicyName(String itemTypeName, DKException original) throws Exception {
        try {
            closeQuietly();
            return normalizePolicy(requireItemType(itemTypeName).getItemTypeRetentionPolicyName());
        } catch (Exception verificationError) {
            if (original != null) {
                original.addSuppressed(verificationError);
                throw original;
            }
            throw verificationError;
        }
    }

    void closeQuietly() {
        if (datastore == null) {
            return;
        }
        try {
            if (datastore.isConnected()) {
                datastore.disconnect();
            }
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
        try {
            datastore.destroy();
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
        datastore = null;
    }

    static String expirationSummary(DKRetentionPolicyDefICM policy) {
        if (!policy.isExpirationEnabled()) {
            return "disabled";
        }
        return policy.getExpirationTimePeriod() + " " + policy.getDefaultExpirationTimeUnit();
    }

    static String normalizePolicy(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static boolean samePolicy(String left, String right) {
        String a = normalizePolicy(left);
        String b = normalizePolicy(right);
        return a == null ? b == null : a.equals(b);
    }

    static String emptyAsDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value;
    }

    private static String classification(short value) {
        switch (value) {
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_ITEM: return "ITEM";
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_RESOURCE_ITEM: return "RESOURCE_ITEM";
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_DOC_MODEL: return "DOC_MODEL";
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_DOC_PART: return "DOC_PART";
            default: return "OTHER(" + value + ")";
        }
    }

    private static String versionControl(short value) {
        switch (value) {
            case DKConstantICM.DK_ICM_VERSION_CONTROL_NEVER: return "NEVER";
            case DKConstantICM.DK_ICM_VERSION_CONTROL_ALWAYS: return "ALWAYS";
            case DKConstantICM.DK_ICM_VERSION_CONTROL_BY_APPLICATION: return "BY_APPLICATION";
            default: return "UNKNOWN(" + value + ")";
        }
    }

    private static String versioningType(short value) {
        switch (value) {
            case DKConstantICM.DK_ICM_ITEM_VERSIONING_FULL: return "FULL";
            case DKConstantICM.DK_ICM_ITEM_VERSIONING_OPTIMIZED: return "OPTIMIZED";
            default: return "UNKNOWN(" + value + ")";
        }
    }

    private static String legacyRetention(DKItemTypeDefICM itemType) {
        int value = itemType.getDefaultItemRetention();
        if (value == 0) {
            return "never expires";
        }
        return value + " " + legacyRetentionUnit(itemType.getDefaultRetentionUnit());
    }

    private static String legacyRetentionUnit(short unit) {
        switch (unit) {
            case 0: return "year(s)";
            case 1: return "month(s)";
            case 2: return "week(s)";
            case 3: return "day(s)";
            default: return "unit(" + unit + ")";
        }
    }

    private static String unitSuffix(Object unit) {
        String value = String.valueOf(unit).toUpperCase(Locale.ROOT);
        if (value.contains("YEAR")) return "y";
        if (value.contains("MONTH")) return "m";
        if (value.contains("WEEK")) return "w";
        if (value.contains("DAY")) return "d";
        return " " + unit;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String oneLine(String value) {
        return safe(value).replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }

    static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }
}
