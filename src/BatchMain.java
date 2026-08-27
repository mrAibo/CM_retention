import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKItemTypeDefICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;

import java.io.BufferedReader;
import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native single-JVM runtime for --file assign/unassign workflows. */
public final class BatchMain {
    private BatchMain() { }

    public static void main(String[] args) {
        int rc = 0;
        CmService cm = null;
        BackfillService backfill = null;
        long totalStarted = Timing.start();
        try {
            BatchOptions options = BatchOptions.parse(args);
            List<String> itemTypes = readItemTypes(options.file);

            Config base = Config.fromEnvironment();
            cm = new CmService(base);
            if (options.backfill) {
                backfill = new BackfillService(BackfillConfig.from(base));
            }

            printHeader(options, itemTypes.size());
            long phase1Started = Timing.start();
            List<BatchEntry> entries = validateAll(cm, backfill, options, itemTypes);
            long phase1Nanos = Timing.elapsed(phase1Started);
            System.out.println("Validation: OK (" + entries.size() + " item types)");
            System.out.println("Phase 1 timing: " + Timing.format(phase1Nanos));

            if (options.dryRun) {
                System.out.println("DRY RUN: batch validation complete. No changes made.");
                System.out.println("Total timing  : " + Timing.since(totalStarted));
                return;
            }

            System.out.println();
            System.out.println("NOTE: batch execution is sequential, not atomic.");
            System.out.println("If a runtime error occurs, processing stops immediately, but earlier successful changes remain committed.");
            if (options.backfill) {
                System.out.println("Each ItemType is processed as: guarded DB2 backfill -> policy fingerprint guard -> assignment -> final verify.");
            }

            if (!approve(options, entries.size())) {
                System.out.println("Cancelled. No changes made.");
                System.out.println("Total timing: " + Timing.since(totalStarted));
                return;
            }

            // Discard CM metadata cached during the potentially long validation
            // phase. The DB2 BackfillService remains open and reuses its one
            // connection across both phases.
            cm.closeQuietly();

            System.out.println();
            System.out.println("Phase 2/2: applying changes");
            System.out.println();

            long phase2Started = Timing.start();
            int completed = 0;
            for (int i = 0; i < entries.size(); i++) {
                BatchEntry entry = entries.get(i);
                long itemStarted = Timing.start();
                System.out.println("--- [" + (i + 1) + "/" + entries.size() + "] " + entry.itemTypeName + " ---");
                try {
                    if (options.command == BatchCommand.ASSIGN) {
                        if (options.backfill) {
                            BackfillWorkflow.apply(cm, backfill, entry.backfill);
                        } else {
                            cm.assignPolicy(
                                    entry.itemTypeName,
                                    options.policyName,
                                    entry.expectedPolicy,
                                    entry.targetPolicyFingerprint,
                                    5);
                            System.out.println("Assigned: " + entry.itemTypeName + " -> " + options.policyName);
                        }
                    } else {
                        cm.unassignPolicy(entry.itemTypeName, entry.expectedPolicy);
                        System.out.println("Unassigned: " + CmService.emptyAsDash(entry.expectedPolicy)
                                + " from " + entry.itemTypeName);
                    }
                    completed++;
                } catch (Exception e) {
                    System.err.println("ERROR: batch stopped at '" + entry.itemTypeName + "' after "
                            + completed + " fully verified item(s).");
                    System.err.println("Earlier successful changes remain committed; review current state before retrying.");
                    throw e;
                } finally {
                    System.out.println("Item timing: " + Timing.since(itemStarted));
                }
                System.out.println();
            }

            long phase2Nanos = Timing.elapsed(phase2Started);
            System.out.println("Batch complete: " + completed + "/" + entries.size()
                    + " item types processed successfully.");
            System.out.println("Phase 2 timing: " + Timing.format(phase2Nanos));
            System.out.println("Total timing  : " + Timing.since(totalStarted));
        } catch (CliException e) {
            System.err.println("ERROR: " + e.getMessage());
            rc = e.exitCode;
        } catch (OperationWarning e) {
            System.err.println("WARNING: " + e.getMessage());
            BackfillMain.printDkException(e.cause);
            rc = 6;
        } catch (SQLException e) {
            BackfillMain.printSqlException(e);
            rc = 3;
        } catch (DKException e) {
            BackfillMain.printDkException(e);
            rc = 3;
        } catch (Exception e) {
            System.err.println("ERROR: " + BackfillMain.safeMessage(e));
            if (Boolean.parseBoolean(System.getenv("CM_DEBUG"))) e.printStackTrace(System.err);
            rc = 3;
        } finally {
            if (backfill != null) backfill.closeQuietly();
            if (cm != null) cm.closeQuietly();
        }
        if (rc != 0) System.exit(rc);
    }

    private static List<BatchEntry> validateAll(CmService cm,
                                                 BackfillService backfill,
                                                 BatchOptions options,
                                                 List<String> itemTypes) throws Exception {
        DKRetentionPolicyDefICM targetPolicy = null;
        PolicyFingerprint targetFingerprint = null;
        if (options.command == BatchCommand.ASSIGN) {
            targetPolicy = cm.requirePolicyFresh(options.policyName);
            targetFingerprint = PolicyFingerprint.from(targetPolicy);
        }

        // One CM metadata collection for Phase 1 replaces one retrieveEntity()
        // round-trip per input line. Writes still re-read each ItemType freshly.
        Map<String, DKItemTypeDefICM> itemTypeMap = cm.itemTypesByName();

        System.out.println("Phase 1/2: validating every item type (no changes)");
        System.out.println();

        List<BatchEntry> entries = new ArrayList<BatchEntry>();
        for (int i = 0; i < itemTypes.size(); i++) {
            String itemTypeName = itemTypes.get(i);
            System.out.println("--- [" + (i + 1) + "/" + itemTypes.size() + "] " + itemTypeName + " ---");

            DKItemTypeDefICM itemType = itemTypeMap.get(itemTypeName);
            if (itemType == null) {
                throw new CliException("Itemtype not found: " + itemTypeName, 4);
            }
            String current = CmService.normalizePolicy(itemType.getItemTypeRetentionPolicyName());

            if (options.command == BatchCommand.ASSIGN) {
                if (options.backfill) {
                    ValidatedBackfill validated = BackfillWorkflow.validate(
                            backfill, itemType, targetPolicy, options.policyName);
                    BackfillMain.printPlan(validated.plan);
                    entries.add(new BatchEntry(
                            itemTypeName, current, validated, targetFingerprint));
                } else {
                    printAssignPlan(itemTypeName, current, options.policyName, targetPolicy);
                    entries.add(new BatchEntry(
                            itemTypeName, current, null, targetFingerprint));
                }
            } else {
                printUnassignPlan(itemTypeName, current);
                entries.add(new BatchEntry(itemTypeName, current, null, null));
            }
            System.out.println();
        }
        return entries;
    }

    private static void printAssignPlan(String itemTypeName,
                                        String current,
                                        String policyName,
                                        DKRetentionPolicyDefICM policy) {
        if (policyName.equals(current)) {
            System.out.println("No change: " + itemTypeName + " already uses " + policyName + ".");
            return;
        }
        System.out.println("Assign retention policy");
        System.out.println("Item type : " + itemTypeName);
        System.out.println("Current   : " + CmService.emptyAsDash(current));
        System.out.println("New       : " + policyName);
        System.out.println("Expiration: " + CmService.expirationSummary(policy));
        System.out.println("Action    : " + (policy.isExpirationEnabled() ? policy.getExpirationAction() : "-"));
        System.out.println("Existing items are not backfilled.");
    }

    private static void printUnassignPlan(String itemTypeName, String current) {
        if (current == null) {
            System.out.println("No change: " + itemTypeName + " has no retention policy.");
            return;
        }
        System.out.println("Unassign retention policy");
        System.out.println("Item type : " + itemTypeName);
        System.out.println("Current   : " + current);
        System.out.println("New       : -");
    }

    private static boolean approve(BatchOptions options, int count) {
        if (options.assumeYes) return true;
        Console console = System.console();
        if (console == null) {
            throw new CliException("Write operation refused without --yes when no terminal is attached", 2);
        }
        String answer = console.readLine("Apply batch to " + count + " item types? [y/N]: ");
        if (answer == null) return false;
        String value = answer.trim();
        return "y".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private static void printHeader(BatchOptions options, int count) {
        System.out.println("Batch mode (native Java runtime)");
        System.out.println("  Operation : " + options.command.cliName);
        System.out.println("  File      : " + options.file);
        System.out.println("  Item types: " + count);
        if (options.command == BatchCommand.ASSIGN) {
            System.out.println("  Policy    : " + options.policyName);
            System.out.println("  Backfill  : " + (options.backfill ? "yes" : "no"));
        }
        System.out.println("  Runtime   : single JVM");
        System.out.println();
    }

    private static List<String> readItemTypes(Path file) throws Exception {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new CliException("ItemType file is not readable: " + file, 2);
        }
        List<String> result = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        try {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                if (!seen.add(value)) {
                    throw new CliException("Duplicate ItemType '" + value + "' in " + file
                            + " (line " + lineNumber + ")", 2);
                }
                result.add(value);
            }
        } finally {
            reader.close();
        }
        if (result.isEmpty()) {
            throw new CliException("No ItemTypes found in " + file, 2);
        }
        return result;
    }

    private enum BatchCommand {
        ASSIGN("assign"),
        UNASSIGN("unassign");

        final String cliName;

        BatchCommand(String cliName) {
            this.cliName = cliName;
        }
    }

    private static final class BatchEntry {
        final String itemTypeName;
        final String expectedPolicy;
        final ValidatedBackfill backfill;
        final PolicyFingerprint targetPolicyFingerprint;

        BatchEntry(String itemTypeName,
                   String expectedPolicy,
                   ValidatedBackfill backfill,
                   PolicyFingerprint targetPolicyFingerprint) {
            this.itemTypeName = itemTypeName;
            this.expectedPolicy = expectedPolicy;
            this.backfill = backfill;
            this.targetPolicyFingerprint = targetPolicyFingerprint;
        }
    }

    private static final class BatchOptions {
        final BatchCommand command;
        final Path file;
        final String policyName;
        final boolean backfill;
        final boolean assumeYes;
        final boolean dryRun;

        BatchOptions(BatchCommand command, Path file, String policyName,
                     boolean backfill, boolean assumeYes, boolean dryRun) {
            this.command = command;
            this.file = file;
            this.policyName = policyName;
            this.backfill = backfill;
            this.assumeYes = assumeYes;
            this.dryRun = dryRun;
        }

        static BatchOptions parse(String[] args) {
            if (args.length == 0) {
                throw new CliException("Internal usage: BatchMain assign|unassign --file ITEMTYPES.txt ...", 2);
            }
            BatchCommand command;
            if ("assign".equals(args[0])) command = BatchCommand.ASSIGN;
            else if ("unassign".equals(args[0])) command = BatchCommand.UNASSIGN;
            else throw new CliException("--file is supported only for assign and unassign", 2);

            String fileValue = null;
            boolean backfill = false;
            boolean yes = false;
            boolean dryRun = false;
            List<String> positional = new ArrayList<String>();

            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if ("--file".equals(arg)) {
                    if (fileValue != null) throw new CliException("Duplicate option --file", 2);
                    if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                        throw new CliException("--file requires a path", 2);
                    }
                    fileValue = args[++i];
                } else if ("--backfill".equals(arg)) {
                    if (backfill) throw new CliException("Duplicate flag --backfill", 2);
                    backfill = true;
                } else if ("--yes".equals(arg)) {
                    if (yes) throw new CliException("Duplicate flag --yes", 2);
                    yes = true;
                } else if ("--dry-run".equals(arg)) {
                    if (dryRun) throw new CliException("Duplicate flag --dry-run", 2);
                    dryRun = true;
                } else if (arg.startsWith("--")) {
                    throw new CliException("Unknown batch option '" + arg + "'", 2);
                } else {
                    positional.add(arg);
                }
            }

            if (fileValue == null || fileValue.trim().isEmpty()) {
                throw new CliException("--file is required for batch mode", 2);
            }
            if (command == BatchCommand.ASSIGN) {
                if (positional.size() != 1) {
                    throw new CliException("Usage: cm-retention assign --file ITEMTYPES.txt POLICY"
                            + " [--backfill] [--dry-run|--yes]", 2);
                }
            } else {
                if (backfill) throw new CliException("--backfill is supported only with assign", 2);
                if (!positional.isEmpty()) {
                    throw new CliException("Usage: cm-retention unassign --file ITEMTYPES.txt"
                            + " [--dry-run|--yes]", 2);
                }
            }

            String policyName = command == BatchCommand.ASSIGN ? positional.get(0) : null;
            return new BatchOptions(command, Paths.get(fileValue), policyName, backfill, yes, dryRun);
        }
    }
}
