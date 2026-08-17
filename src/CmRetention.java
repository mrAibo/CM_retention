import com.ibm.mm.sdk.common.DKConstant;
import com.ibm.mm.sdk.common.DKConstantICM;
import com.ibm.mm.sdk.common.DKDatastoreAdminICM;
import com.ibm.mm.sdk.common.DKDatastoreDefICM;
import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKItemTypeDefICM;
import com.ibm.mm.sdk.common.DKPolicyMgmtICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_EXPIRATION_ACTION_TYPE;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_POLICY_TIME_UNIT;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_RETENTION_TYPE;
import com.ibm.mm.sdk.common.DKNVPair;
import com.ibm.mm.sdk.common.dkCollection;
import com.ibm.mm.sdk.common.dkIterator;
import com.ibm.mm.sdk.server.DKDatastoreICM;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local CLI for IBM Content Manager 8.7 retention-policy administration.
 *
 * Credentials are read from the selected .env file or process environment.
 * The password is never accepted as a command-line argument.
 */
public final class CmRetention {
    private static final String VERSION = "0.1.2";

    private final Config config;
    private DKDatastoreICM datastore;

    private CmRetention(Config config) {
        this.config = config;
    }

    public static void main(String[] args) {
        int exitCode = 0;
        CmRetention app = null;

        try {
            Config config = Config.fromEnvironment();
            app = new CmRetention(config);
            app.run(args);
        } catch (CliException e) {
            System.err.println("ERROR: " + e.getMessage());
            exitCode = e.exitCode;
        } catch (OperationWarning e) {
            System.err.println("WARNING: " + e.getMessage());
            printDkDiagnostic(e.cause);
            exitCode = 6;
        } catch (DKException e) {
            printDkException(e);
            exitCode = 3;
        } catch (Exception e) {
            System.err.println("ERROR: " + safeMessage(e));
            if (Boolean.parseBoolean(System.getenv("CM_DEBUG"))) {
                e.printStackTrace(System.err);
            }
            exitCode = 3;
        } finally {
            if (app != null) {
                app.closeQuietly();
            }
        }

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private void run(String[] args) throws Exception {
        if (args.length == 0 || isHelp(args[0])) {
            printHelp();
            return;
        }

        if ("version".equals(args[0]) || "--version".equals(args[0])) {
            System.out.println("cm-retention " + VERSION);
            return;
        }

        if (args.length < 2) {
            throw new CliException("Missing subcommand. Run: cm-retention help", 2);
        }

        String area = args[0];
        String action = args[1];
        String[] tail = Arrays.copyOfRange(args, 2, args.length);

        if ("connection".equals(area)) {
            requireAction(action, "test");
            requireNoArgs(tail);
            connectionTest();
            return;
        }

        if ("itemtype".equals(area)) {
            runItemType(action, tail);
            return;
        }

        if ("policy".equals(area)) {
            runPolicy(action, tail);
            return;
        }

        throw new CliException("Unknown command area: " + area, 2);
    }

    private void runItemType(String action, String[] args) throws Exception {
        if ("list".equals(action)) {
            requireNoArgs(args);
            itemTypeList();
        } else if ("show".equals(action)) {
            requireArgCount(args, 1, "itemtype show <ITEMTYPE>");
            itemTypeShow(args[0]);
        } else if ("assign".equals(action)) {
            ParsedArgs parsed = ParsedArgs.parse(args);
            requirePositional(parsed, 2, "itemtype assign <ITEMTYPE> <POLICY> --yes");
            requireConfirmation(parsed);
            itemTypeAssign(parsed.positional.get(0), parsed.positional.get(1));
        } else if ("unassign".equals(action)) {
            ParsedArgs parsed = ParsedArgs.parse(args);
            requirePositional(parsed, 1, "itemtype unassign <ITEMTYPE> --yes");
            requireConfirmation(parsed);
            itemTypeUnassign(parsed.positional.get(0));
        } else {
            throw new CliException("Unknown itemtype action: " + action, 2);
        }
    }

    private void runPolicy(String action, String[] args) throws Exception {
        if ("list".equals(action)) {
            requireNoArgs(args);
            policyList();
        } else if ("show".equals(action)) {
            requireArgCount(args, 1, "policy show <POLICY>");
            policyShow(args[0]);
        } else if ("usage".equals(action)) {
            requireArgCount(args, 1, "policy usage <POLICY>");
            policyUsage(args[0]);
        } else if ("create".equals(action)) {
            ParsedArgs parsed = ParsedArgs.parse(args);
            requirePositional(parsed, 1,
                    "policy create <POLICY> --expiration <AGE> [options] --yes");
            requireConfirmation(parsed);
            policyCreate(parsed.positional.get(0), parsed);
        } else if ("delete".equals(action)) {
            ParsedArgs parsed = ParsedArgs.parse(args);
            requirePositional(parsed, 1, "policy delete <POLICY> --yes");
            requireConfirmation(parsed);
            policyDelete(parsed.positional.get(0));
        } else {
            throw new CliException("Unknown policy action: " + action, 2);
        }
    }

    private DKDatastoreICM datastore() throws Exception {
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

    private void connectionTest() throws Exception {
        DKDatastoreICM ds = datastore();
        System.out.println("Connection: OK");
        System.out.println("Database:   " + ds.datastoreName());
        System.out.println("User:       " + ds.userName());
        System.out.println("CM API:     " + DKConstantICM.DK_ICM_RELEASE_VERSION);
    }

    private void itemTypeList() throws Exception {
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

    private List<DKItemTypeDefICM> listItemTypes() throws Exception {
        DKNVPair[] options = new DKNVPair[] {
                new DKNVPair(DKConstantICM.DK_ICM_ENTITY_TYPE,
                        Integer.valueOf(DKConstantICM.DK_ICM_BASE)),
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

    private DKItemTypeDefICM requireItemType(String name) throws Exception {
        DKItemTypeDefICM itemType = (DKItemTypeDefICM) datastoreDef().retrieveEntity(name);
        if (itemType == null) {
            throw new CliException("Itemtype not found: " + name, 4);
        }
        return itemType;
    }

    private void itemTypeShow(String name) throws Exception {
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

    private void itemTypeAssign(String itemTypeName, String policyName) throws Exception {
        DKItemTypeDefICM itemType = requireItemType(itemTypeName);
        requirePolicy(policyName);

        String current = itemType.getItemTypeRetentionPolicyName();
        if (policyName.equals(current)) {
            System.out.println("No change: itemtype already uses policy " + policyName);
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
                                + " Check ICMSERVER.log before treating the operation as fully successful.",
                        e);
            }
            throw e;
        }

        String persisted = readPersistedPolicyName(itemTypeName, null);
        if (!policyName.equals(persisted)) {
            throw new CliException("Verification failed: itemtype " + itemTypeName
                    + " uses " + emptyAsDash(persisted) + " instead of " + policyName, 6);
        }
        System.out.println("Assigned policy: " + persisted);
        System.out.println("Itemtype:        " + itemTypeName);
        System.out.println("Note: the policy applies to newly created items/versions; existing items are not backfilled.");
    }

    private void itemTypeUnassign(String itemTypeName) throws Exception {
        DKItemTypeDefICM itemType = requireItemType(itemTypeName);
        String current = itemType.getItemTypeRetentionPolicyName();
        if (current == null || current.trim().isEmpty()) {
            System.out.println("No change: itemtype has no retention policy.");
            return;
        }

        itemType.setItemTypeRetentionPolicyName("");
        try {
            itemType.update();
            datastore().commit();
        } catch (DKException e) {
            String persisted = readPersistedPolicyName(itemTypeName, e);
            if (persisted == null || persisted.trim().isEmpty()) {
                throw new OperationWarning(
                        "Policy " + current + " was removed from itemtype " + itemTypeName
                                + ", but IBM CM reported a secondary error after persisting the change."
                                + " Check ICMSERVER.log before treating the operation as fully successful.",
                        e);
            }
            throw e;
        }

        String persisted = readPersistedPolicyName(itemTypeName, null);
        if (persisted != null && !persisted.trim().isEmpty()) {
            throw new CliException("Verification failed: itemtype " + itemTypeName
                    + " still uses policy " + persisted, 6);
        }
        System.out.println("Unassigned policy " + current + " from itemtype " + itemTypeName);
    }

    private String readPersistedPolicyName(String itemTypeName, DKException original) throws Exception {
        try {
            closeQuietly();
            DKItemTypeDefICM refreshed = requireItemType(itemTypeName);
            return refreshed.getItemTypeRetentionPolicyName();
        } catch (Exception verificationError) {
            if (original != null) {
                original.addSuppressed(verificationError);
                throw original;
            }
            throw verificationError;
        }
    }

    private void policyList() throws Exception {
        String[] names = policyManager().listRetentionPolicyNames();
        List<String> sorted = new ArrayList<String>();
        if (names != null) {
            sorted.addAll(Arrays.asList(names));
        }
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);

        System.out.printf("%-32s %-12s %-12s %-16s %s%n",
                "POLICY", "TYPE", "EXPIRATION", "ACTION", "ITEMTYPES");
        System.out.printf("%-32s %-12s %-12s %-16s %s%n",
                repeat('-', 32), repeat('-', 12), repeat('-', 12), repeat('-', 16), repeat('-', 9));

        for (String name : sorted) {
            DKRetentionPolicyDefICM policy = policyManager().retrieveRetentionPolicy(name);
            String expiration = policy.isExpirationEnabled()
                    ? policy.getExpirationTimePeriod() + unitSuffix(policy.getDefaultExpirationTimeUnit())
                    : "disabled";
            String[] itemTypes = policyManager().listItemTypeNamesByRetentionPolicy(name);
            List<String> assigned = new ArrayList<String>();
            if (itemTypes != null) {
                assigned.addAll(Arrays.asList(itemTypes));
            }
            Collections.sort(assigned, String.CASE_INSENSITIVE_ORDER);

            System.out.printf("%-32s %-12s %-12s %-16s %d%n",
                    safe(policy.getName()),
                    safe(String.valueOf(policy.getRetentionType())),
                    expiration,
                    policy.isExpirationEnabled() ? safe(String.valueOf(policy.getExpirationAction())) : "-",
                    assigned.size());

            if (!assigned.isEmpty()) {
                System.out.println("  Assigned itemtypes: " + join(assigned, ", "));
            }
        }

        System.out.println();
        System.out.println("Count: " + sorted.size());
    }

    private DKRetentionPolicyDefICM requirePolicy(String name) throws Exception {
        String[] names = policyManager().listRetentionPolicyNames();
        if (names != null) {
            for (String existing : names) {
                if (name.equals(existing)) {
                    return policyManager().retrieveRetentionPolicy(name);
                }
            }
        }
        throw new CliException("Policy not found: " + name, 4);
    }

    private void policyShow(String name) throws Exception {
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
        printPolicyUsage(name);
    }

    private void policyUsage(String name) throws Exception {
        requirePolicy(name);
        printPolicyUsage(name);
    }

    private void printPolicyUsage(String name) throws Exception {
        String[] itemTypes = policyManager().listItemTypeNamesByRetentionPolicy(name);
        List<String> sorted = new ArrayList<String>();
        if (itemTypes != null) {
            sorted.addAll(Arrays.asList(itemTypes));
        }
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);

        System.out.println("Assigned itemtypes:         " + sorted.size());
        for (String itemType : sorted) {
            System.out.println("  - " + itemType);
        }
    }

    private void policyCreate(String name, ParsedArgs args) throws Exception {
        if (policyExists(name)) {
            throw new CliException("Policy already exists: " + name, 5);
        }

        String ageValue = args.requiredOption("expiration");
        Age age = Age.parse(ageValue);
        String schedule = args.option("schedule", "0 2 * * *");
        int commitCount = positiveInt(args.option("commit-count", "100"), "commit-count");
        int maxItems = nonNegativeInt(args.option("max-items", "5000"), "max-items");
        int maxDuration = positiveInt(args.option("max-duration", "120"), "max-duration");
        boolean forceCheckin = args.flag("force-checkin");

        DKRetentionPolicyDefICM policy = new DKRetentionPolicyDefICM();
        policy.setName(name);
        policy.setRetentionType(DK_ICM_RETENTION_TYPE.FIXED_TIME);
        policy.setRetentionEnabled(false);
        policy.setExpirationEnabled(true);
        policy.setExpirationTimePeriod(age.amount);
        policy.setDefaultExpirationTimeUnit(age.unit);
        policy.setExpirationAction(DK_ICM_EXPIRATION_ACTION_TYPE.AUTO_DELETE);
        policy.setDeleteExpiredItemsForceCheckInEnabled(forceCheckin);
        policy.setDeleteExpiredItemsMaximumDuration(maxDuration);
        policy.setDeleteExpiredItemsMaximumRows(maxItems);
        policy.setDeleteExpiredItemsCommitCount(commitCount);
        policy.setDeleteExpiredItemsScheduleInformation(schedule);

        policyManager().add(policy);
        datastore().commit();
        policyManager().clearCache();

        System.out.println("Created auto-delete policy: " + name);
        policyShow(name);
    }

    private void policyDelete(String name) throws Exception {
        requirePolicy(name);
        String[] usage = policyManager().listItemTypeNamesByRetentionPolicy(name);
        if (usage != null && usage.length > 0) {
            List<String> sorted = new ArrayList<String>(Arrays.asList(usage));
            Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
            throw new CliException("Policy is assigned to " + usage.length
                    + " itemtype(s): " + join(sorted, ", ")
                    + ". Unassign it first.", 5);
        }

        policyManager().del(name);
        datastore().commit();
        policyManager().clearCache();
        System.out.println("Deleted policy: " + name);
    }

    private boolean policyExists(String name) throws Exception {
        String[] names = policyManager().listRetentionPolicyNames();
        if (names == null) {
            return false;
        }
        for (String existing : names) {
            if (name.equals(existing)) {
                return true;
            }
        }
        return false;
    }

    private void closeQuietly() {
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

    private static void printHelp() {
        System.out.println("cm-retention " + VERSION + " - IBM Content Manager 8.7 retention CLI");
        System.out.println();
        System.out.println("Read-only commands:");
        System.out.println("  cm-retention connection test");
        System.out.println("  cm-retention itemtype list");
        System.out.println("  cm-retention itemtype show <ITEMTYPE>");
        System.out.println("  cm-retention policy list");
        System.out.println("  cm-retention policy show <POLICY>");
        System.out.println("  cm-retention policy usage <POLICY>");
        System.out.println();
        System.out.println("Write commands (require --yes):");
        System.out.println("  cm-retention policy create <POLICY> --expiration <AGE> [options] --yes");
        System.out.println("  cm-retention policy delete <POLICY> --yes");
        System.out.println("  cm-retention itemtype assign <ITEMTYPE> <POLICY> --yes");
        System.out.println("  cm-retention itemtype unassign <ITEMTYPE> --yes");
        System.out.println();
        System.out.println("Create options:");
        System.out.println("  --expiration 1y|12m|52w|365d   Required");
        System.out.println("  --schedule \"0 2 * * *\"       DB2 schedule; default: daily 02:00");
        System.out.println("  --commit-count 100             Default: 100");
        System.out.println("  --max-items 5000               Default: 5000; 0 means unlimited");
        System.out.println("  --max-duration 120             Default: 120 minutes");
        System.out.println("  --force-checkin                Disabled by default");
        System.out.println();
        System.out.println("Configuration is read from .env; see .env.example.");
    }

    private static boolean isHelp(String value) {
        return "help".equals(value) || "--help".equals(value) || "-h".equals(value);
    }

    private static void requireAction(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new CliException("Expected action: " + expected, 2);
        }
    }

    private static void requireNoArgs(String[] args) {
        if (args.length != 0) {
            throw new CliException("Unexpected argument: " + args[0], 2);
        }
    }

    private static void requireArgCount(String[] args, int count, String usage) {
        if (args.length != count) {
            throw new CliException("Usage: cm-retention " + usage, 2);
        }
    }

    private static void requirePositional(ParsedArgs args, int count, String usage) {
        if (args.positional.size() != count) {
            throw new CliException("Usage: cm-retention " + usage, 2);
        }
    }

    private static void requireConfirmation(ParsedArgs args) {
        if (!args.flag("yes")) {
            throw new CliException("Write operation refused without --yes", 2);
        }
    }

    private static int positiveInt(String value, String option) {
        int parsed = parseInt(value, option);
        if (parsed <= 0) {
            throw new CliException("--" + option + " must be greater than zero", 2);
        }
        return parsed;
    }

    private static int nonNegativeInt(String value, String option) {
        int parsed = parseInt(value, option);
        if (parsed < 0) {
            throw new CliException("--" + option + " must not be negative", 2);
        }
        return parsed;
    }

    private static int parseInt(String value, String option) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CliException("Invalid integer for --" + option + ": " + value, 2);
        }
    }

    private static String classification(short value) {
        switch (value) {
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_ITEM:
                return "ITEM";
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_RESOURCE_ITEM:
                return "RESOURCE_ITEM";
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_DOC_MODEL:
                return "DOC_MODEL";
            case DKConstantICM.DK_ICM_ITEMTYPE_CLASS_DOC_PART:
                return "DOC_PART";
            default:
                return "OTHER(" + value + ")";
        }
    }

    private static String versionControl(short value) {
        switch (value) {
            case DKConstantICM.DK_ICM_VERSION_CONTROL_NEVER:
                return "NEVER";
            case DKConstantICM.DK_ICM_VERSION_CONTROL_ALWAYS:
                return "ALWAYS";
            case DKConstantICM.DK_ICM_VERSION_CONTROL_BY_APPLICATION:
                return "BY_APPLICATION";
            default:
                return "UNKNOWN(" + value + ")";
        }
    }

    private static String versioningType(short value) {
        switch (value) {
            case DKConstantICM.DK_ICM_ITEM_VERSIONING_FULL:
                return "FULL";
            case DKConstantICM.DK_ICM_ITEM_VERSIONING_OPTIMIZED:
                return "OPTIMIZED";
            default:
                return "UNKNOWN(" + value + ")";
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
            case 0:
                return "year(s)";
            case 1:
                return "month(s)";
            case 2:
                return "week(s)";
            case 3:
                return "day(s)";
            default:
                return "unit(" + unit + ")";
        }
    }

    private static String unitSuffix(DK_ICM_POLICY_TIME_UNIT unit) {
        String value = String.valueOf(unit).toUpperCase(Locale.ROOT);
        if (value.contains("YEAR")) {
            return "y";
        }
        if (value.contains("MONTH")) {
            return "m";
        }
        if (value.contains("WEEK")) {
            return "w";
        }
        if (value.contains("DAY")) {
            return "d";
        }
        return " " + unit;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String emptyAsDash(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value;
    }

    private static String oneLine(String value) {
        return safe(value).replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getName()
                : message;
    }

    private static void printDkException(DKException e) {
        System.err.println("IBM CM ERROR");
        printDkDetails(e);
    }

    private static void printDkDiagnostic(DKException e) {
        System.err.println("IBM CM diagnostic:");
        printDkDetails(e);
    }

    private static void printDkDetails(DKException e) {
        System.err.println("Name:        " + e.name());
        System.err.println("Message:     " + e.getMessage());
        System.err.println("Message ID:  " + e.getErrorId());
        System.err.println("Error state: " + e.errorState());
        System.err.println("Error code:  " + e.errorCode());
        if (Boolean.parseBoolean(System.getenv("CM_DEBUG"))) {
            e.printStackTrace(System.err);
        }
    }

    private static final class Config {
        final String database;
        final String user;
        final String password;

        Config(String database, String user, String password) {
            this.database = database;
            this.user = user;
            this.password = password;
        }

        static Config fromEnvironment() throws IOException {
            Map<String, String> fileValues = loadEnvFile();
            String database = requiredValue("CM_DATABASE", fileValues);
            String user = requiredValue("CM_USER", fileValues);
            String password = requiredValue("CM_PASSWORD", fileValues);
            return new Config(database, user, password);
        }

        private static Map<String, String> loadEnvFile() throws IOException {
            String configuredPath = System.getenv("CM_RETENTION_ENV_FILE");
            Path path = configuredPath == null || configuredPath.trim().isEmpty()
                    ? Paths.get(".env")
                    : Paths.get(configuredPath);

            if (!Files.isRegularFile(path)) {
                throw new CliException("Configuration file not found: " + path, 2);
            }

            Map<String, String> values = new LinkedHashMap<String, String>();
            BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            try {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }

                    int separator = trimmed.indexOf('=');
                    if (separator <= 0) {
                        throw new CliException("Invalid .env line " + lineNumber + ": expected KEY=VALUE", 2);
                    }

                    String key = trimmed.substring(0, separator).trim();
                    String value = trimmed.substring(separator + 1).trim();
                    if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        throw new CliException("Invalid .env key on line " + lineNumber + ": " + key, 2);
                    }

                    if (value.length() >= 2) {
                        char first = value.charAt(0);
                        char last = value.charAt(value.length() - 1);
                        if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                            value = value.substring(1, value.length() - 1);
                        }
                    }
                    values.put(key, value);
                }
            } finally {
                reader.close();
            }
            return values;
        }

        private static String requiredValue(String name, Map<String, String> fileValues) {
            String value = System.getenv(name);
            if (value == null || value.trim().isEmpty()) {
                value = fileValues.get(name);
            }
            if (value == null || value.trim().isEmpty()) {
                throw new CliException("Missing configuration: " + name, 2);
            }
            return value;
        }
    }

    private static final class ParsedArgs {
        final List<String> positional = new ArrayList<String>();
        final Map<String, String> options = new LinkedHashMap<String, String>();
        final List<String> flags = new ArrayList<String>();

        static ParsedArgs parse(String[] args) {
            ParsedArgs parsed = new ParsedArgs();
            for (int i = 0; i < args.length; i++) {
                String current = args[i];
                if (!current.startsWith("--")) {
                    parsed.positional.add(current);
                    continue;
                }

                String key = current.substring(2);
                if (key.isEmpty()) {
                    throw new CliException("Invalid option: " + current, 2);
                }

                if ("yes".equals(key) || "force-checkin".equals(key)) {
                    parsed.flags.add(key);
                    continue;
                }

                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new CliException("Missing value for --" + key, 2);
                }
                parsed.options.put(key, args[++i]);
            }
            return parsed;
        }

        String requiredOption(String name) {
            String value = options.get(name);
            if (value == null || value.trim().isEmpty()) {
                throw new CliException("Missing required option: --" + name, 2);
            }
            return value;
        }

        String option(String name, String defaultValue) {
            String value = options.get(name);
            return value == null ? defaultValue : value;
        }

        boolean flag(String name) {
            return flags.contains(name);
        }
    }

    private static final class Age {
        final int amount;
        final DK_ICM_POLICY_TIME_UNIT unit;

        Age(int amount, DK_ICM_POLICY_TIME_UNIT unit) {
            this.amount = amount;
            this.unit = unit;
        }

        static Age parse(String value) {
            if (value == null) {
                throw new CliException("Expiration age is missing", 2);
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!normalized.matches("[1-9][0-9]*[ymwd]")) {
                throw new CliException("Invalid age '" + value
                        + "'. Expected for example: 1y, 12m, 52w or 365d", 2);
            }

            int amount;
            try {
                amount = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
            } catch (NumberFormatException e) {
                throw new CliException("Invalid age: " + value, 2);
            }

            char suffix = normalized.charAt(normalized.length() - 1);
            switch (suffix) {
                case 'y':
                    return new Age(amount, DK_ICM_POLICY_TIME_UNIT.YEAR);
                case 'm':
                    return new Age(amount, DK_ICM_POLICY_TIME_UNIT.MONTH);
                case 'w':
                    return new Age(amount, DK_ICM_POLICY_TIME_UNIT.WEEK);
                case 'd':
                    return new Age(amount, DK_ICM_POLICY_TIME_UNIT.DAY);
                default:
                    throw new CliException("Unsupported age unit: " + suffix, 2);
            }
        }
    }

    private static final class OperationWarning extends Exception {
        private static final long serialVersionUID = 1L;
        final DKException cause;

        OperationWarning(String message, DKException cause) {
            super(message, cause);
            this.cause = cause;
        }
    }

    private static final class CliException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int exitCode;

        CliException(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }
    }
}
