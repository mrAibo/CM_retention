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

/**
 * Independent post-batch verifier.
 *
 * This class is intentionally launched in a second JVM by bin/cm-retention.
 * It therefore cannot reuse CmService/session/cache state from BatchMain.
 */
public final class BatchVerifyMain {
    private BatchVerifyMain() { }

    public static void main(String[] args) {
        int rc = 0;
        CmService cm = null;
        try {
            VerifyOptions options = VerifyOptions.parse(args);
            List<String> itemTypes = readItemTypes(options.file);
            cm = new CmService(Config.fromEnvironment());

            // Fail early if a fresh independent CM session cannot be established.
            cm.datastore();

            System.out.println();
            System.out.println("Phase 3/3: independent final verification");
            System.out.println("  Runtime   : new JVM / fresh CM session");
            System.out.println("  Operation : " + options.command);
            System.out.println("  Expected  : " + CmService.emptyAsDash(options.expectedPolicy));
            System.out.println("  Item types: " + itemTypes.size());
            System.out.println();

            int ok = 0;
            List<Mismatch> mismatches = new ArrayList<Mismatch>();
            List<VerifyError> errors = new ArrayList<VerifyError>();

            for (String itemTypeName : itemTypes) {
                try {
                    DKItemTypeDefICM itemType = cm.requireItemType(itemTypeName);
                    String actual = CmService.normalizePolicy(itemType.getItemTypeRetentionPolicyName());
                    if (samePolicy(options.expectedPolicy, actual)) {
                        ok++;
                    } else {
                        mismatches.add(new Mismatch(itemTypeName, actual));
                    }
                } catch (Exception e) {
                    errors.add(new VerifyError(itemTypeName, safeMessage(e)));
                    // Do not let one broken SDK read poison verification of later
                    // ItemTypes. Reconnect before the next exact read.
                    cm.closeQuietly();
                }
            }

            System.out.println("Final verification summary");
            System.out.println("  Verified OK         : " + ok);
            System.out.println("  State mismatches    : " + mismatches.size());
            System.out.println("  Verification errors : " + errors.size());

            if (!mismatches.isEmpty()) {
                System.out.println();
                System.out.println("Confirmed state mismatches:");
                for (Mismatch mismatch : mismatches) {
                    System.out.println("  - " + mismatch.itemTypeName
                            + " expected=" + CmService.emptyAsDash(options.expectedPolicy)
                            + " actual=" + CmService.emptyAsDash(mismatch.actualPolicy));
                }
                writeRetryFile(mismatches);
            }

            if (!errors.isEmpty()) {
                System.out.println();
                System.out.println("Verification errors (state unknown; not added to retry file):");
                for (VerifyError error : errors) {
                    System.out.println("  - " + error.itemTypeName + ": " + error.message);
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
            if (cm != null) cm.closeQuietly();
        }
        if (rc != 0) System.exit(rc);
    }

    static boolean samePolicy(String expected, String actual) {
        String left = CmService.normalizePolicy(expected);
        String right = CmService.normalizePolicy(actual);
        return left == null ? right == null : left.equals(right);
    }

    private static void writeRetryFile(List<Mismatch> mismatches) throws Exception {
        String value = System.getenv("CM_RETENTION_RETRY_FILE");
        if (value == null || value.trim().isEmpty() || mismatches.isEmpty()) return;

        Path retry = Paths.get(value).toAbsolutePath().normalize();
        Path parent = retry.getParent();
        if (parent != null) Files.createDirectories(parent);

        List<String> lines = new ArrayList<String>();
        lines.add("# Generated by cm-retention final verifier");
        lines.add("# Contains confirmed state mismatches only; verification errors are intentionally excluded.");
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

    private static final class Mismatch {
        final String itemTypeName;
        final String actualPolicy;

        Mismatch(String itemTypeName, String actualPolicy) {
            this.itemTypeName = itemTypeName;
            this.actualPolicy = actualPolicy;
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

        VerifyOptions(String command, Path file, String expectedPolicy) {
            this.command = command;
            this.file = file;
            this.expectedPolicy = expectedPolicy;
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
            List<String> positional = new ArrayList<String>();
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if ("--file".equals(arg)) {
                    if (fileValue != null) throw new CliException("Duplicate option --file", 2);
                    if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                        throw new CliException("--file requires a path", 2);
                    }
                    fileValue = args[++i];
                } else if ("--yes".equals(arg) || "--dry-run".equals(arg) || "--backfill".equals(arg)) {
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
            } else if (!positional.isEmpty()) {
                throw new CliException("unassign final verification does not accept a policy name", 2);
            }

            return new VerifyOptions(command, Paths.get(fileValue), expectedPolicy);
        }
    }
}
