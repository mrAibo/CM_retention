import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKItemTypeDefICM;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Independent post-batch verifier.
 *
 * This class is intentionally launched in a second JVM by bin/cm-retention.
 * It therefore cannot reuse CmService/session/cache state from BatchMain.
 */
public final class BatchVerifyMain {
    private static final int DISPLAY_LIMIT = 20;
    private static final Pattern STOP_PATTERN = Pattern.compile(
            "ERROR: batch stopped at '([^']+)' after ([0-9]+) verified item\\(s\\).*");

    private BatchVerifyMain() { }

    public static void main(String[] args) {
        int rc = 0;
        CmService cm = null;
        BackfillService backfill = null;
        try {
            VerifyOptions options = VerifyOptions.parse(args);
            List<String> itemTypes = readItemTypes(options.file);
            BatchStop batchStop = readBatchStop(itemTypes);

            Config base = Config.fromEnvironment();
            cm = new CmService(base);
            if (options.backfill) {
                backfill = new BackfillService(BackfillConfig.from(base));
            }

            // Fail early if a fresh independent CM session cannot be established.
            cm.datastore();

            System.out.println();
            System.out.println("Phase 3/3: independent final verification");
            System.out.println("  Runtime   : new JVM / fresh CM session");
            System.out.println("  Operation : " + options.command);
            System.out.println("  Expected  : " + CmService.emptyAsDash(options.expectedPolicy));
            System.out.println("  Backfill  : " + (options.backfill ? "yes" : "no"));
            System.out.println("  Item types: " + itemTypes.size());
            if (batchStop != null) {
                System.out.println("  Batch stop : " + batchStop.failedItemType
                        + " after " + batchStop.verifiedBeforeStop + " verified item(s)");
            }
            System.out.println();

            int ok = 0;
            List<Mismatch> mismatches = new ArrayList<Mismatch>();
            List<VerifyError> errors = new ArrayList<VerifyError>();

            for (int i = 0; i < itemTypes.size(); i++) {
                String itemTypeName = itemTypes.get(i);
                try {
                    DKItemTypeDefICM itemType = cm.requireItemType(itemTypeName);
                    String actual = CmService.normalizePolicy(itemType.getItemTypeRetentionPolicyName());
                    if (!samePolicy(options.expectedPolicy, actual)) {
                        mismatches.add(new Mismatch(
                                itemTypeName, actual,
                                classifyMismatch(i, itemTypeName, batchStop), -1L));
                        continue;
                    }

                    if (options.backfill) {
                        long remaining = backfill.remainingMissingForIndependentVerification(
                                itemType, actual, options.expectedPolicy);
                        if (remaining != 0) {
                            mismatches.add(new Mismatch(
                                    itemTypeName, actual,
                                    classifyMismatch(i, itemTypeName, batchStop), remaining));
                            continue;
                        }
                    }
                    ok++;
                } catch (Exception e) {
                    errors.add(new VerifyError(itemTypeName, safeMessage(e)));
                    // Do not let one broken SDK/DB read poison verification of
                    // later ItemTypes. Reconnect before the next exact read.
                    cm.closeQuietly();
                    if (backfill != null) backfill.closeQuietly();
                }
            }

            int failedAtStop = countKind(mismatches, MismatchKind.FAILED_AT_STOP);
            int unattempted = countKind(mismatches, MismatchKind.UNATTEMPTED);
            int postWriteMismatch = countKind(mismatches, MismatchKind.POST_WRITE_MISMATCH);
            int unknownMismatch = countKind(mismatches, MismatchKind.UNKNOWN);
            int backfillResiduals = countBackfillResiduals(mismatches);

            System.out.println("Final verification summary");
            System.out.println("  Verified OK         : " + ok);
            System.out.println("  State mismatches    : " + mismatches.size());
            if (options.backfill) {
                System.out.println("  Backfill residuals  : " + backfillResiduals);
            }
            if (batchStop != null) {
                System.out.println("    Failed at stop    : " + failedAtStop);
                System.out.println("    Not attempted     : " + unattempted);
                System.out.println("    Post-write mismatch: " + postWriteMismatch);
            } else if (unknownMismatch > 0) {
                System.out.println("    Unclassified      : " + unknownMismatch);
            }
            System.out.println("  Verification errors : " + errors.size());

            if (!mismatches.isEmpty()) {
                System.out.println();
                if (batchStop != null) {
                    printMismatchGroup("Failed item (batch stopped here):",
                            mismatches, MismatchKind.FAILED_AT_STOP, options.expectedPolicy);
                    printMismatchGroup("Unexpected mismatch among previously verified items:",
                            mismatches, MismatchKind.POST_WRITE_MISMATCH, options.expectedPolicy);
                    printMismatchGroup("Not attempted because the batch is fail-fast:",
                            mismatches, MismatchKind.UNATTEMPTED, options.expectedPolicy);
                } else {
                    printMismatchGroup("Confirmed state/backfill mismatches:",
                            mismatches, MismatchKind.UNKNOWN, options.expectedPolicy);
                }
                writeRetryFile(mismatches, options.backfill);
            }

            if (!errors.isEmpty()) {
                System.out.println();
                System.out.println("Verification errors (state unknown; not added to retry file):");
                int shown = Math.min(errors.size(), DISPLAY_LIMIT);
                for (int i = 0; i < shown; i++) {
                    VerifyError error = errors.get(i);
                    System.out.println("  - " + error.itemTypeName + ": " + error.message);
                }
                if (errors.size() > shown) {
                    System.out.println("  ... (+" + (errors.size() - shown) + " more)");
                }
            }

            if (mismatches.isEmpty() && errors.isEmpty()) {
                System.out.println("Final verification: OK (" + ok + "/" + itemTypes.size() + ")");
            } else {
                System.out.println("Final verification: NOT CLEAN; returning exit 6.");
                rc = 6;
            }
        } catch (CliException e) {
            System.err.println("ERROR: final verifier: " + e.getMessage());
            rc = e.exitCode;
        } catch (DKException e) {
            System.err.println("ERROR: final verifier could not establish/read a fresh CM session.");
            BackfillMain.printDkException(e);
            rc = 3;
        } catch (Exception e) {
            System.err.println("ERROR: final verifier: " + safeMessage(e));
            if (Boolean.parseBoolean(System.getenv("CM_DEBUG"))) {
                e.printStackTrace(System.err);
            }
            rc = 3;
        } finally {
            if (backfill != null) backfill.closeQuietly();
            if (cm != null) cm.closeQuietly();
        }
        if (rc != 0) System.exit(rc);
    }

    static boolean samePolicy(String expected, String actual) {
        String left = CmService.normalizePolicy(expected);
        String right = CmService.normalizePolicy(actual);
        return left == null ? right == null : left.equals(right);
    }

    static String mismatchKindForTest(int index, String itemTypeName,
                                      int verifiedBeforeStop, String failedItemType) {
        BatchStop stop = failedItemType == null ? null
                : new BatchStop(failedItemType, verifiedBeforeStop);
        return classifyMismatch(index, itemTypeName, stop).name();
    }

    private static MismatchKind classifyMismatch(int index,
                                                 String itemTypeName,
                                                 BatchStop stop) {
        if (stop == null) return MismatchKind.UNKNOWN;
        if (index < stop.verifiedBeforeStop) return MismatchKind.POST_WRITE_MISMATCH;
        if (index == stop.verifiedBeforeStop && itemTypeName.equals(stop.failedItemType)) {
            return MismatchKind.FAILED_AT_STOP;
        }
        if (index > stop.verifiedBeforeStop) return MismatchKind.UNATTEMPTED;
        return MismatchKind.UNKNOWN;
    }

    private static BatchStop readBatchStop(List<String> itemTypes) {
        String retryValue = System.getenv("CM_RETENTION_RETRY_FILE");
        if (retryValue == null || retryValue.trim().isEmpty()) return null;
        String retry = retryValue.trim();
        if (!retry.endsWith("-retry.txt")) return null;

        Path audit = Paths.get(retry.substring(0, retry.length() - "-retry.txt".length()) + ".log")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(audit) || !Files.isReadable(audit)) return null;

        BatchStop last = null;
        try {
            BufferedReader reader = Files.newBufferedReader(audit, StandardCharsets.UTF_8);
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = STOP_PATTERN.matcher(line.trim());
                    if (!matcher.matches()) continue;
                    String failed = matcher.group(1);
                    int verified = Integer.parseInt(matcher.group(2));
                    if (verified >= 0 && verified < itemTypes.size()
                            && failed.equals(itemTypes.get(verified))) {
                        last = new BatchStop(failed, verified);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception ignored) {
            // Classification metadata is supplemental. Verification itself must
            // still run even if the audit log cannot be parsed.
            return null;
        }
        return last;
    }

    private static int countKind(List<Mismatch> mismatches, MismatchKind kind) {
        int count = 0;
        for (Mismatch mismatch : mismatches) {
            if (mismatch.kind == kind) count++;
        }
        return count;
    }

    private static int countBackfillResiduals(List<Mismatch> mismatches) {
        int count = 0;
        for (Mismatch mismatch : mismatches) {
            if (mismatch.remainingRows >= 0) count++;
        }
        return count;
    }

    private static void printMismatchGroup(String title,
                                           List<Mismatch> mismatches,
                                           MismatchKind kind,
                                           String expectedPolicy) {
        List<Mismatch> selected = new ArrayList<Mismatch>();
        for (Mismatch mismatch : mismatches) {
            if (mismatch.kind == kind) selected.add(mismatch);
        }
        if (selected.isEmpty()) return;

        System.out.println(title + " " + selected.size());
        int shown = Math.min(selected.size(), DISPLAY_LIMIT);
        for (int i = 0; i < shown; i++) {
            Mismatch mismatch = selected.get(i);
            String suffix = mismatch.remainingRows >= 0
                    ? " remaining-null=" + mismatch.remainingRows : "";
            System.out.println("  - " + mismatch.itemTypeName
                    + " expected=" + CmService.emptyAsDash(expectedPolicy)
                    + " actual=" + CmService.emptyAsDash(mismatch.actualPolicy)
                    + suffix);
        }
        if (selected.size() > shown) {
            System.out.println("  ... (+" + (selected.size() - shown)
                    + " more; full list is in the retry file)");
        }
        System.out.println();
    }

    private static void writeRetryFile(List<Mismatch> mismatches, boolean backfill) throws Exception {
        String value = System.getenv("CM_RETENTION_RETRY_FILE");
        if (value == null || value.trim().isEmpty() || mismatches.isEmpty()) return;

        Path retry = Paths.get(value).toAbsolutePath().normalize();
        Path parent = retry.getParent();
        if (parent != null) Files.createDirectories(parent);

        List<String> lines = new ArrayList<String>();
        lines.add("# Generated by cm-retention final verifier");
        lines.add("# Contains every confirmed mismatch that still needs the requested final state"
                + (backfill ? " and/or complete backfill." : "."));
        lines.add("# Verification errors are intentionally excluded because their state is unknown.");
        for (Mismatch mismatch : mismatches) {
            lines.add(mismatch.itemTypeName);
        }
        Files.write(retry, lines, StandardCharsets.UTF_8);
        System.out.println("Retry file            : " + retry);
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
                String itemType = line.trim();
                if (itemType.isEmpty() || itemType.startsWith("#")) continue;
                if (!seen.add(itemType)) {
                    throw new CliException("Duplicate ItemType '" + itemType + "' in " + file
                            + " (line " + lineNumber + ")", 2);
                }
                result.add(itemType);
            }
        } finally {
            reader.close();
        }
        if (result.isEmpty()) throw new CliException("No ItemTypes found in " + file, 2);
        return result;
    }

    private static String safeMessage(Throwable e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getName() : message;
    }

    private enum MismatchKind {
        FAILED_AT_STOP,
        UNATTEMPTED,
        POST_WRITE_MISMATCH,
        UNKNOWN
    }

    private static final class BatchStop {
        final String failedItemType;
        final int verifiedBeforeStop;

        BatchStop(String failedItemType, int verifiedBeforeStop) {
            this.failedItemType = failedItemType;
            this.verifiedBeforeStop = verifiedBeforeStop;
        }
    }

    private static final class Mismatch {
        final String itemTypeName;
        final String actualPolicy;
        final MismatchKind kind;
        final long remainingRows;

        Mismatch(String itemTypeName,
                 String actualPolicy,
                 MismatchKind kind,
                 long remainingRows) {
            this.itemTypeName = itemTypeName;
            this.actualPolicy = actualPolicy;
            this.kind = kind;
            this.remainingRows = remainingRows;
        }
    }

    private static final class VerifyError {
        final String itemTypeName;
        final String message;

        VerifyError(String itemTypeName, String message) {
            this.itemTypeName = itemTypeName;
            this.message = message;
        }
    }

    private static final class VerifyOptions {
        final String command;
        final Path file;
        final String expectedPolicy;
        final boolean backfill;

        VerifyOptions(String command, Path file, String expectedPolicy, boolean backfill) {
            this.command = command;
            this.file = file;
            this.expectedPolicy = expectedPolicy;
            this.backfill = backfill;
        }

        static VerifyOptions parse(String[] args) {
            if (args == null || args.length == 0) {
                throw new CliException("Internal verifier usage requires assign|unassign batch arguments", 2);
            }
            String command = args[0];
            if (!"assign".equals(command) && !"unassign".equals(command)) {
                throw new CliException("Final verifier supports only assign/unassign batch operations", 2);
            }

            String fileValue = null;
            boolean backfill = false;
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
                    if (backfill) throw new CliException("Duplicate option --backfill", 2);
                    backfill = true;
                } else if ("--yes".equals(arg) || "--dry-run".equals(arg)) {
                    // Execution-only flags do not change the expected final policy state.
                } else if (arg.startsWith("--")) {
                    throw new CliException("Unknown verifier batch option '" + arg + "'", 2);
                } else {
                    positional.add(arg);
                }
            }

            if (fileValue == null || fileValue.trim().isEmpty()) {
                throw new CliException("--file is required for final batch verification", 2);
            }

            String expectedPolicy = null;
            if ("assign".equals(command)) {
                if (positional.size() != 1) {
                    throw new CliException("assign final verification requires exactly one policy name", 2);
                }
                expectedPolicy = positional.get(0);
            } else {
                if (backfill) {
                    throw new CliException("unassign final verification does not support --backfill", 2);
                }
                if (!positional.isEmpty()) {
                    throw new CliException("unassign final verification does not accept a policy name", 2);
                }
            }

            return new VerifyOptions(command, Paths.get(fileValue), expectedPolicy, backfill);
        }
    }
}
