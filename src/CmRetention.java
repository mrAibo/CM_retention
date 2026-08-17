import com.ibm.mm.sdk.common.DKException;

public final class CmRetention {
    static final String VERSION = "0.2.0";

    private CmRetention() {}

    public static void main(String[] args) {
        int exitCode = 0;
        CmService service = null;
        try {
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
