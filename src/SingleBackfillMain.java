import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKItemTypeDefICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;

import java.io.Console;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** One-JVM runtime for top-level assign ITEMTYPE POLICY --backfill. */
public final class SingleBackfillMain {
    private SingleBackfillMain() { }

    public static void main(String[] args) {
        int rc = 0;
        CmService cm = null;
        BackfillService backfill = null;
        long totalStarted = Timing.start();
        try {
            Options options = Options.parse(args);
            Config base = Config.fromEnvironment();
            cm = new CmService(base);
            backfill = new BackfillService(BackfillConfig.from(base));

            long planStarted = Timing.start();
            DKItemTypeDefICM itemType = cm.requireItemType(options.itemTypeName);
            DKRetentionPolicyDefICM policy = cm.requirePolicyFresh(options.policyName);
            ValidatedBackfill validated = BackfillWorkflow.validate(
                    backfill, itemType, policy, options.policyName);
            System.out.println("Backfill database           : " + backfill.databaseDisplayName());
            BackfillMain.printPlan(validated.plan);
            System.out.println("Plan timing                : " + Timing.since(planStarted));

            if (options.dryRun) {
                System.out.println();
                System.out.println("DRY RUN: backfill and assignment were validated. No changes made.");
                System.out.println("Total timing               : " + Timing.since(totalStarted));
                return;
            }

            System.out.println();
            System.out.println("IMPORTANT: existing rows whose calculated date is already in the past become immediately eligible for AUTO_DELETE after policy assignment.");
            if (!approve(options)) {
                System.out.println("Cancelled. No changes made.");
                return;
            }

            cm.closeQuietly();
            System.out.println();
            BackfillWorkflow.apply(cm, backfill, validated);
            System.out.println("Total workflow timing      : " + Timing.since(totalStarted));
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

    private static boolean approve(Options options) {
        if (options.assumeYes) return true;
        Console console = System.console();
        if (console == null) {
            throw new CliException("Write operation refused without --yes when no terminal is attached", 2);
        }
        String answer = console.readLine(
                "Backfill existing rows and assign " + options.policyName
                        + " to " + options.itemTypeName + "? [y/N]: ");
        if (answer == null) return false;
        String value = answer.trim();
        return "y".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private static final class Options {
        final String itemTypeName;
        final String policyName;
        final boolean assumeYes;
        final boolean dryRun;

        Options(String itemTypeName, String policyName, boolean assumeYes, boolean dryRun) {
            this.itemTypeName = itemTypeName;
            this.policyName = policyName;
            this.assumeYes = assumeYes;
            this.dryRun = dryRun;
        }

        static Options parse(String[] args) {
            if (args.length == 0 || !"assign".equals(args[0])) {
                throw new CliException(
                        "Usage: cm-retention assign ITEMTYPE POLICY --backfill [--dry-run|--yes]", 2);
            }
            boolean backfill = false;
            boolean yes = false;
            boolean dryRun = false;
            List<String> positional = new ArrayList<String>();

            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if ("--backfill".equals(arg)) {
                    if (backfill) throw new CliException("Duplicate flag --backfill", 2);
                    backfill = true;
                } else if ("--yes".equals(arg)) {
                    if (yes) throw new CliException("Duplicate flag --yes", 2);
                    yes = true;
                } else if ("--dry-run".equals(arg)) {
                    if (dryRun) throw new CliException("Duplicate flag --dry-run", 2);
                    dryRun = true;
                } else if (arg.startsWith("--")) {
                    throw new CliException("Unknown backfill option '" + arg + "'", 2);
                } else {
                    positional.add(arg);
                }
            }

            if (!backfill || positional.size() != 2) {
                throw new CliException(
                        "Usage: cm-retention assign ITEMTYPE POLICY --backfill [--dry-run|--yes]", 2);
            }
            return new Options(positional.get(0), positional.get(1), yes, dryRun);
        }
    }
}
