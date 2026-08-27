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
import java.util.Set;

/**
 * Native batch runtime for --file assign/unassign workflows.
 *
 * The launcher starts this class once for the complete batch. Phase 1 validates
 * every ItemType before any mutation. Phase 2 remains sequential and fail-fast
 * so the existing operational safety model is preserved.
 */
public final class BatchMain {
    private BatchMain() { }

    public static void main(String[] args) {
        int rc = 0;
        CmService cm = null;
        BackfillService backfill = null;
        try {
            BatchOptions options = BatchOptions.parse(args);
            List<String> itemTypes = readItemTypes(options.file);

            Config base = Config.fromEnvironment();
            cm = new CmService(base);
            if (options.backfill) {
                backfill = new BackfillService(BackfillConfig.from(base));
            }

            printHeader(options, itemTypes.size());
            List<BatchEntry> entries = validateAll(cm, backfill, options, itemTypes);
            System.out.println("Validation: OK (" + entries.size() + " item types)");

            if (options.dryRun) {
                System.out.println("DRY RUN: batch validation complete. No changes made.");
                return;
            }

            System.out.println();
            System.out.println("NOTE: batch execution is sequential, not atomic.");
            System.out.println("If a runtime error occurs, processing stops immediately, but earlier successful changes remain committed.");
            if (options.backfill) {
                System.out.println("Each ItemType is processed as: DB2 backfill -> verify -> policy assignment -> final verify.");
            }

            if (!approve(options, entries.size())) {
                System.out.println("Cancelled. No changes made.");
                return;
            }

            // Discard any metadata cached during the potentially long validation
            // phase. Each write still performs CmService's persisted-state
            // reconnect verification after commit.
            cm.closeQuietly();

            System.out.println();
            System.out.println("Phase 2/2: applying changes");
            System.out.println();

            int completed = 0;
            for (int i = 0; i < entries.size(); i++) {
                BatchEntry entry = entries.get(i);
                System.out.println("--- [" + (i + 1) + "/" + entries.size() + "] " + entry.itemTypeName + " ---");
                try {
                    if (options.command == BatchCommand.ASSIGN) {
                        if (options.backfill) {
                            applyBackfillAssignment(cm, backfill, entry, options.policyName);
                        } else {
                            cm.assignPolicy(entry.itemTypeName, options.policyName, entry.expectedPolicy);
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
                }
                System.out.println();
            }

            System.out.println("Batch complete: " + completed + "/" + entries.size()
                    + " item types processed successfully.");
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
        if (options.command == BatchCommand.ASSIGN) {
            // Resolve once for Phase 1 instead of once per ItemType/JVM.
            targetPolicy = cm.requirePolicy(options.policyName);
        }

        System.out.println("Phase 1/2: validating every item type (no changes)");
        System.out.println();

        List<BatchEntry> entries = new ArrayList<BatchEntry>();
        for (int i = 0; i < itemTypes.size(); i++) {
            String itemTypeName = itemTypes.get(i);
            System.out.println("--- [" + (i + 1) + "/" + itemTypes.size() + "] " + itemTypeName + " ---");

            DKItemTypeDefICM itemType = cm.requireItemType(itemTypeName);
            String current = CmService.normalizePolicy(itemType.getItemTypeRetentionPolicyName());
            BackfillPlan backfillPlan = null;

            if (options.command == BatchCommand.ASSIGN) {
                if (options.backfill) {
                    backfillPlan = backfill.plan(itemType, targetPolicy, current, options.policyName);
                    BackfillMain.printPlan(backfillPlan);
                    BackfillMain.validatePlan(backfillPlan);
                } else {
                    printAssignPlan(itemTypeName, current, options.policyName, targetPolicy);
                }
            } else {
                printUnassignPlan(itemTypeName, current);
            }

            entries.add(new BatchEntry(itemTypeName, current, backfillPlan));
            System.out.println();
        }
        return entries;
    }

    private static void applyBackfillAssignment(CmService cm,
                                                BackfillService backfill,
                                                BatchEntry entry,
                                                String policyName) throws Exception {
        // Re-read immediately before the write. This keeps the stale-plan guard
        // even though the entire batch now runs in one JVM.
        DKItemTypeDefICM itemType = cm.requireItemType(entry.itemTypeName);
        String current = CmService.normalizePolicy(itemType.getItemTypeRetentionPolicyName());
        if (!samePolicy(current, entry.expectedPolicy)) {
            throw new CliException("State changed after batch validation: " + entry.itemTypeName
                    + " now uses " + CmService.emptyAsDash(current)
                    + " instead of " + CmService.emptyAsDash(entry.expectedPolicy)
                    + ". Re-run the batch.", 5);
        }

        DKRetentionPolicyDefICM policy = cm.requirePolicy(policyName);
        BackfillPlan currentPlan = backfill.plan(itemType, policy, current, policyName);
        BackfillMain.validatePlan(currentPlan);
        validateBackfillSemanticsUnchanged(entry.backfillPlan, currentPlan);

        BackfillMain.printApplyHeader(currentPlan);
        BackfillResult result = backfill.apply(currentPlan);
        System.out.println("Backfill committed : " + result.updatedRows + " row(s)");
        System.out.println("Remaining NULL rows: " + result.remainingRows);

        Exception assignmentProblem = null;
        try {
            cm.assignPolicy(entry.itemTypeName, policyName, current);
        } catch (Exception e) {
            assignmentProblem = e;
            printEmbeddedProblem("Policy assignment", e);
        }

        Exception verificationProblem = null;
        try {
            DKItemTypeDefICM freshItem = cm.requireItemType(entry.itemTypeName);
            DKRetentionPolicyDefICM freshPolicy = cm.requirePolicy(policyName);
            String persisted = CmService.normalizePolicy(freshItem.getItemTypeRetentionPolicyName());
            long remaining = backfill.remainingMissing(freshItem, freshPolicy, persisted, policyName);
            if (remaining != 0) {
                throw new CliException("Backfill verification failed: " + remaining
                        + " row(s) still have NULL retention/auto-delete metadata.", 6);
            }
            BackfillMain.printVerificationOk(entry.itemTypeName, policyName);
        } catch (Exception e) {
            verificationProblem = e;
            printEmbeddedProblem("Final verification", e);
        }

        if (assignmentProblem != null || verificationProblem != null) {
            throw new CliException("Backfill phase completed for " + entry.itemTypeName
                    + ", but assignment/final verification was not a clean success."
                    + " Review the ItemType and DB2 state before retrying.", 6);
        }
    }

    private static void validateBackfillSemanticsUnchanged(BackfillPlan validated,
                                                            BackfillPlan current) {
        if (validated == null) {
            throw new CliException("Internal batch error: missing validated backfill plan", 2);
        }
        boolean same = validated.itemTypeId == current.itemTypeId
                && validated.componentTypeId == current.componentTypeId
                && validated.segmentId == current.segmentId
                && validated.tableName.equals(current.tableName)
                && validated.expirationAmount == current.expirationAmount
                && validated.expirationUnit.equals(current.expirationUnit)
                && validated.durationSql.equals(current.durationSql);
        if (!same) {
            throw new CliException("Backfill metadata or policy semantics changed after batch validation for "
                    + current.itemTypeName + ". Re-run the batch and review the new plan.", 5);
        }
    }

    private static void printEmbeddedProblem(String stage, Exception e) {
        if (e instanceof OperationWarning) {
            OperationWarning warning = (OperationWarning) e;
            System.err.println("WARNING: " + stage + ": " + warning.getMessage());
            BackfillMain.printDkException(warning.cause);
        } else if (e instanceof DKException) {
            System.err.println("ERROR: " + stage + " failed");
            BackfillMain.printDkException((DKException) e);
        } else if (e instanceof SQLException) {
            System.err.println("ERROR: " + stage + " failed");
            BackfillMain.printSqlException((SQLException) e);
        } else {
            System.err.println("ERROR: " + stage + ": " + BackfillMain.safeMessage(e));
        }
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

    private static boolean samePolicy(String left, String right) {
        String a = CmService.normalizePolicy(left);
        String b = CmService.normalizePolicy(right);
        return a == null ? b == null : a.equals(b);
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
        final BackfillPlan backfillPlan;

        BatchEntry(String itemTypeName, String expectedPolicy, BackfillPlan backfillPlan) {
            this.itemTypeName = itemTypeName;
            this.expectedPolicy = expectedPolicy;
            this.backfillPlan = backfillPlan;
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
