import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKItemTypeDefICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;

import java.sql.SQLException;

/** Shared guarded backfill execution used by single-item and --file workflows. */
final class BackfillWorkflow {
    private BackfillWorkflow() { }

    static ValidatedBackfill validate(BackfillService backfill,
                                      DKItemTypeDefICM itemType,
                                      DKRetentionPolicyDefICM policy,
                                      String targetPolicy) throws Exception {
        String current = CmService.normalizePolicy(itemType.getItemTypeRetentionPolicyName());
        BackfillPlan plan = backfill.plan(itemType, policy, current, targetPolicy);
        BackfillMain.validatePlan(plan);
        return new ValidatedBackfill(itemType.getName(), targetPolicy, current, plan);
    }

    static void apply(CmService cm,
                      BackfillService backfill,
                      ValidatedBackfill validated) throws Exception {
        long totalStarted = Timing.start();

        long preflightStarted = Timing.start();
        DKItemTypeDefICM itemType = cm.requireItemType(validated.itemTypeName);
        String current = CmService.normalizePolicy(itemType.getItemTypeRetentionPolicyName());
        requireExpectedAssignmentState(validated, current, 5,
                "after validation and before database backfill");

        DKRetentionPolicyDefICM policy = cm.requirePolicyFresh(validated.policyName);
        validated.plan.policyFingerprint.requireSame(
                PolicyFingerprint.from(policy), "after validation and before database backfill", 5);

        BackfillWritePlan writePlan = backfill.prepareWrite(
                itemType, policy, current, validated.policyName, validated.plan);
        long preflightNanos = Timing.elapsed(preflightStarted);

        printApplyHeader(backfill, writePlan);
        long dbStarted = Timing.start();
        BackfillResult result = backfill.apply(writePlan);
        long dbNanos = Timing.elapsed(dbStarted);
        System.out.println("Backfill committed : " + result.updatedRows + " row(s)");
        System.out.println("Remaining NULL rows: " + result.remainingRows);

        // After a database mutation, any newly detected stale state is a partial-
        // success condition. If zero rows changed, no database data was modified
        // and a normal stale-plan refusal (5) remains accurate.
        int postBackfillExit = result.changedRows() ? 6 : 5;

        long guardStarted = Timing.start();
        DKItemTypeDefICM preAssignItem;
        String preAssignCurrent;
        try {
            cm.closeQuietly();
            preAssignItem = cm.requireItemType(validated.itemTypeName);
            preAssignCurrent = CmService.normalizePolicy(preAssignItem.getItemTypeRetentionPolicyName());
            requireExpectedAssignmentState(
                    validated, preAssignCurrent, postBackfillExit,
                    "after database COMMIT and before policy assignment");

            DKRetentionPolicyDefICM preAssignPolicy = cm.requirePolicyFresh(validated.policyName);
            validated.plan.policyFingerprint.requireSame(
                    PolicyFingerprint.from(preAssignPolicy),
                    "after database COMMIT and before policy assignment", postBackfillExit);
            backfill.requireRootUnchanged(
                    preAssignItem, validated.plan.rootFingerprint,
                    "after database COMMIT and before policy assignment", postBackfillExit);
        } catch (Exception e) {
            if (result.changedRows()) {
                printEmbeddedProblem("Post-commit safety guard", e);
                throw new CliException("Backfill was committed for " + validated.itemTypeName
                        + ", but the post-commit safety guard failed. Policy assignment was not started."
                        + " Review the ItemType, policy and database state before retrying.", 6);
            }
            throw e;
        }
        long guardNanos = Timing.elapsed(guardStarted);

        Exception assignmentProblem = null;
        long cmStarted = Timing.start();
        try {
            cm.assignPolicy(
                    validated.itemTypeName,
                    validated.policyName,
                    preAssignCurrent,
                    validated.plan.policyFingerprint,
                    postBackfillExit);
        } catch (Exception e) {
            assignmentProblem = e;
            printEmbeddedProblem("Policy assignment", e);
        }
        long cmNanos = Timing.elapsed(cmStarted);

        Exception verificationProblem = null;
        long verifyStarted = Timing.start();
        try {
            // Force a new CM session/cache view for the final persisted-state
            // check. This also detects a policy changed during the assignment.
            cm.closeQuietly();
            DKItemTypeDefICM freshItem = cm.requireItemType(validated.itemTypeName);
            DKRetentionPolicyDefICM freshPolicy = cm.requirePolicyFresh(validated.policyName);
            validated.plan.policyFingerprint.requireSame(
                    PolicyFingerprint.from(freshPolicy), "during final verification", 6);

            String persisted = CmService.normalizePolicy(freshItem.getItemTypeRetentionPolicyName());
            long remaining = backfill.remainingMissing(
                    freshItem, validated.plan.rootFingerprint, persisted, validated.policyName);
            if (remaining != 0) {
                throw new CliException("Backfill verification failed: " + remaining
                        + " row(s) still have NULL retention/auto-delete metadata.", 6);
            }
            BackfillMain.printVerificationOk(validated.itemTypeName, validated.policyName);
        } catch (Exception e) {
            verificationProblem = e;
            printEmbeddedProblem("Final verification", e);
        }
        long verifyNanos = Timing.elapsed(verifyStarted);

        if (assignmentProblem != null || verificationProblem != null) {
            throw new CliException("Backfill phase completed for " + validated.itemTypeName
                    + ", but assignment/final verification was not a clean success."
                    + " Review the ItemType and database state before retrying.", 6);
        }

        System.out.println("Timing                    : preflight " + Timing.format(preflightNanos)
                + " / " + backfill.databaseDisplayName() + " " + Timing.format(dbNanos)
                + " / post-commit guard " + Timing.format(guardNanos)
                + " / CM " + Timing.format(cmNanos)
                + " / verify " + Timing.format(verifyNanos)
                + " / total " + Timing.since(totalStarted));
    }

    private static void requireExpectedAssignmentState(ValidatedBackfill validated,
                                                       String actual,
                                                       int exitCode,
                                                       String stage) {
        if (!samePolicy(actual, validated.expectedCurrentPolicy)) {
            throw new CliException("ItemType state changed " + stage + ": "
                    + validated.itemTypeName + " now uses " + CmService.emptyAsDash(actual)
                    + " instead of " + CmService.emptyAsDash(validated.expectedCurrentPolicy)
                    + ". Re-run the operation.", exitCode);
        }
    }

    private static void printApplyHeader(BackfillService backfill, BackfillWritePlan plan) {
        System.out.println("Applying existing-item backfill");
        System.out.println("  Database         : " + backfill.databaseDisplayName());
        System.out.println("  Item type        : " + plan.itemTypeName);
        System.out.println("  Table            : " + plan.rootFingerprint.tableName);
        System.out.println("  Formula          : ICM$AUTODELETEDATE = CREATETS + " + plan.durationSql);
        System.out.println("  Planned eligible : " + plan.plannedFillableRows);
    }

    static void printEmbeddedProblem(String stage, Exception e) {
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

    private static boolean samePolicy(String left, String right) {
        String a = CmService.normalizePolicy(left);
        String b = CmService.normalizePolicy(right);
        return a == null ? b == null : a.equals(b);
    }
}

final class ValidatedBackfill {
    final String itemTypeName;
    final String policyName;
    final String expectedCurrentPolicy;
    final BackfillPlan plan;

    ValidatedBackfill(String itemTypeName,
                      String policyName,
                      String expectedCurrentPolicy,
                      BackfillPlan plan) {
        this.itemTypeName = itemTypeName;
        this.policyName = policyName;
        this.expectedCurrentPolicy = expectedCurrentPolicy;
        this.plan = plan;
    }
}
