import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKItemTypeDefICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;

import java.sql.SQLException;

/** Internal helper invoked by the launcher for explicit --backfill workflows. */
public final class BackfillMain {
    private BackfillMain() { }

    public static void main(String[] args) {
        int rc = 0;
        CmService cm = null;
        BackfillService backfill = null;
        try {
            if (args.length != 3) {
                throw new CliException("Internal usage: BackfillMain plan|apply|verify ITEMTYPE POLICY", 2);
            }
            String action = args[0];
            String itemTypeName = args[1];
            String policyName = args[2];

            Config base = Config.fromEnvironment();
            cm = new CmService(base);
            backfill = new BackfillService(BackfillConfig.from(base));

            DKItemTypeDefICM itemType = cm.requireItemType(itemTypeName);
            DKRetentionPolicyDefICM policy = cm.requirePolicy(policyName);
            String current = CmService.normalizePolicy(itemType.getItemTypeRetentionPolicyName());

            if ("plan".equals(action)) {
                BackfillPlan plan = backfill.plan(itemType, policy, current, policyName);
                printPlan(plan);
                validatePlan(plan);
            } else if ("apply".equals(action)) {
                BackfillPlan plan = backfill.plan(itemType, policy, current, policyName);
                printApplyHeader(plan);
                validatePlan(plan);
                BackfillResult result = backfill.apply(plan);
                System.out.println("Backfill committed : " + result.updatedRows + " row(s)");
                System.out.println("Remaining NULL rows: " + result.remainingRows);
            } else if ("verify".equals(action)) {
                // remainingMissing() also validates the target assignment,
                // supported policy semantics, root component and SegmentID.
                // Avoid rebuilding the full seven-counter plan just to verify.
                long remaining = backfill.remainingMissing(itemType, policy, current, policyName);
                if (remaining != 0) {
                    throw new CliException("Backfill verification failed: " + remaining
                            + " row(s) still have NULL retention/auto-delete metadata.", 6);
                }
                printVerificationOk(itemTypeName, policyName);
            } else {
                throw new CliException("Unknown internal backfill action: " + action, 2);
            }
        } catch (CliException e) {
            System.err.println("ERROR: " + e.getMessage());
            rc = e.exitCode;
        } catch (SQLException e) {
            printSqlException(e);
            rc = 3;
        } catch (DKException e) {
            printDkException(e);
            rc = 3;
        } catch (Exception e) {
            System.err.println("ERROR: " + safeMessage(e));
            if (Boolean.parseBoolean(System.getenv("CM_DEBUG"))) e.printStackTrace(System.err);
            rc = 3;
        } finally {
            if (backfill != null) backfill.closeQuietly();
            if (cm != null) cm.closeQuietly();
        }
        if (rc != 0) System.exit(rc);
    }

    static void validatePlan(BackfillPlan plan) {
        validateSegment(plan);
        if (plan.missingCreateTimestampRows > 0) {
            throw new CliException("Backfill refused: " + plan.missingCreateTimestampRows
                    + " row(s) have NULL CREATETS.", 5);
        }
    }

    static void validateSegment(BackfillPlan plan) {
        if (plan.segmentId != 1) {
            throw new CliException("Backfill refused: ItemType uses component SegmentID "
                    + plan.segmentId + ". Multi-segment backfill is not implemented; refusing"
                    + " to update only one segment.", 5);
        }
    }

    static void printPlan(BackfillPlan plan) {
        System.out.println("Existing-item backfill plan\n");
        System.out.println("Item type                 : " + plan.itemTypeName);
        System.out.println("Current policy            : " + CmService.emptyAsDash(plan.currentPolicy));
        System.out.println("Target policy             : " + plan.policyName);
        System.out.println("ItemType ID               : " + plan.itemTypeId);
        System.out.println("Root component ID         : " + plan.componentTypeId);
        System.out.println("Component SegmentID       : " + plan.segmentId);
        System.out.println("Root table                : " + plan.tableName);
        System.out.println("Expiration                : " + plan.expirationAmount + " " + plan.expirationUnit);
        System.out.println("Formula                   : ICM$AUTODELETEDATE = CREATETS + " + plan.durationSql);
        System.out.println();
        System.out.println("Root rows total           : " + plan.totalRows);
        System.out.println("Missing both dates        : " + plan.missingRows);
        System.out.println("Backfillable rows         : " + plan.fillableRows);
        System.out.println("NULL create timestamp     : " + plan.missingCreateTimestampRows);
        System.out.println("Immediately expired after : " + plan.immediatelyExpiredRows);
        System.out.println("Already auto-delete dated : " + plan.existingAutoDeleteRows);
        System.out.println("Retention date already set: " + plan.retentionDateRows);
        System.out.println();
        System.out.println("Only rows where ICM$RETENTIONDATE and ICM$AUTODELETEDATE are both NULL are changed.");
    }

    static void printApplyHeader(BackfillPlan plan) {
        System.out.println("Applying existing-item backfill");
        System.out.println("  Item type : " + plan.itemTypeName);
        System.out.println("  Table     : " + plan.tableName);
        System.out.println("  Formula   : ICM$AUTODELETEDATE = CREATETS + " + plan.durationSql);
        System.out.println("  Eligible  : " + plan.fillableRows);
    }

    static void printVerificationOk(String itemTypeName, String policyName) {
        System.out.println("Backfill verification: OK");
        System.out.println("Policy assignment    : " + itemTypeName + " -> " + policyName);
        System.out.println("Remaining NULL rows  : 0");
    }

    static void printSqlException(SQLException e) {
        System.err.println("DB2 ERROR");
        System.err.println("Message:    " + e.getMessage());
        System.err.println("SQL state:  " + e.getSQLState());
        System.err.println("Error code: " + e.getErrorCode());
    }

    static void printDkException(DKException e) {
        System.err.println("IBM CM ERROR");
        System.err.println("Name:        " + e.name());
        System.err.println("Message:     " + e.getMessage());
        System.err.println("Message ID:  " + e.getErrorId());
        System.err.println("Error state: " + e.errorState());
        System.err.println("Error code:  " + e.errorCode());
    }

    static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getName() : message;
    }
}
