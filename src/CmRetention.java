import com.ibm.mm.sdk.common.DKException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public final class CmRetention {
    static final String VERSION = "0.3.3";

    private CmRetention() {}

    public static void main(String[] rawArgs) {
        int exitCode = 0;
        CmService service = null;
        try {
            String[] args = normalizeCreateTemplateArgs(rawArgs);
            printCreateShortcutHelp(args);
            if (CmCli.handleHelpOrVersionWithoutConfig(args)) {
                return;
            }
            if (args.length == 0 && System.console() == null) {
                CmCli.printHelp();
                return;
            }

            Config config = Config.fromEnvironment();
            service = new CmService(config);
            new CmCli(config, service).run(args);
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
            if (service != null) {
                service.closeQuietly();
            }
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Convenience syntax:
     *
     *   cm-retention create profiles/auto-delete-5y.properties --dry-run
     *
     * is normalized to the explicit, already-supported form:
     *
     *   cm-retention create --properties profiles/auto-delete-5y.properties --dry-run
     *
     * Detection is intentionally conservative: a candidate must end in
     * .properties and already exist as a readable regular file. Explicit
     * --properties always wins and is left untouched. More than one automatic
     * template candidate is rejected as ambiguous.
     */
    private static String[] normalizeCreateTemplateArgs(String[] args) {
        if (args == null || args.length < 2 || !"create".equals(args[0])) {
            return args == null ? new String[0] : args;
        }
        for (String arg : args) {
            if ("--properties".equals(arg)) {
                return args;
            }
        }

        int candidateIndex = -1;
        for (int i = 1; i < args.length; i++) {
            String candidate = args[i];
            if (candidate == null || candidate.startsWith("--")
                    || !candidate.toLowerCase(Locale.ROOT).endsWith(".properties")) {
                continue;
            }
            Path path = Paths.get(candidate);
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                continue;
            }
            if (candidateIndex >= 0) {
                throw new CliException("Multiple readable .properties files supplied to create; use --properties FILE explicitly", 2);
            }
            candidateIndex = i;
        }

        if (candidateIndex < 0) {
            return args;
        }

        String[] normalized = new String[args.length + 1];
        int target = 0;
        for (int i = 0; i < args.length; i++) {
            if (i == candidateIndex) {
                normalized[target++] = "--properties";
            }
            normalized[target++] = args[i];
        }
        return normalized;
    }

    private static void printCreateShortcutHelp(String[] args) {
        if (args.length < 2 || !"create".equals(args[0])) return;
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println("Shortcut: cm-retention create FILE.properties [options]");
                System.out.println("          A readable .properties file is detected automatically.\n");
                return;
            }
        }
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

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getName() : message;
    }
}
