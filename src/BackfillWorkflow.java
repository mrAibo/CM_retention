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

    static void apply(final CmService cm,
                      final BackfillService backfill,
                      final ValidatedBackfill validated) throws Exception {
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
        BackfillResult result;
        if (Db2ChunkedBackfill.shouldUse(backfill, writePlan)) {
            result = Db2ChunkedBackfill.apply(backfill, writePlan,
                    preAssignmentCommitGuard(cm, backfill, validated));
        } else {
            result = backfill.apply(writePlan);
        }
        long dbNanos = Timing.elapsed(dbStarted);
        System.out.println("Backfill committed : " + result.updatedRows + " row(s)");
        System.out.println("Remaining NULL rows: " + result.remainingRows);

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
            if (!(e instanceof OperationWarning)) {
                printEmbeddedProblem("Policy assignment", e);
            }
        }
        long cmNanos = Timing.elapsed(cmStarted);

        Exception verificationProblem = null;
        long verifyStarted = Timing.start();
        try {
            finalVerifyAndCatchUp(cm, backfill, validated);
        } catch (Exception e) {
            verificationProblem = e;
            printEmbeddedProblem("Final verification", e);
        }
        long verifyNanos = Timing.elapsed(verifyStarted);

        if (verificationProblem != null) {
            if (assignmentProblem instanceof OperationWarning) {
                printEmbeddedProblem("Policy assignment", assignmentProblem);
            }
            throw new CliException("Backfill phase completed for " + validated.itemTypeName
                    + ", but final verification was not a clean success."
                    + " Review the ItemType and database state before retrying.", 6);
        }

        if (assignmentProblem != null && !(assignmentProblem instanceof OperationWarning)) {
            throw new CliException("Backfill phase completed for " + validated.itemTypeName
                    + ", but policy assignment was not a clean success."
                    + " Review the ItemType and database state before retrying.", 6);
        }

        printTiming(backfill, preflightNanos, dbNanos, guardNanos, cmNanos, verifyNanos, totalStarted);

        if (assignmentProblem instanceof OperationWarning) {
            throw (OperationWarning) assignmentProblem;
        }
    }

    private static Db2ChunkedBackfill.CommitGuard preAssignmentCommitGuard(
            final CmService cm,
            final BackfillService backfill,
            final ValidatedBackfill validated) {
        return new Db2ChunkedBackfill.CommitGuard() {
            @Override
            public void verify(long committedRows) throws Exception {
                cm.closeQuietly();
                DKItemTypeDefICM chunkItem = cm.requireItemType(validated.itemTypeName);
                String chunkCurrent = CmService.normalizePolicy(
                        chunkItem.getItemTypeRetentionPolicyName());
                requireExpectedAssignmentState(
                        validated, chunkCurrent, 6,
                        "after DB2 chunk COMMIT (" + committedRows + " row(s) committed)");

                DKRetentionPolicyDefICM chunkPolicy = cm.requirePolicyFresh(validated.policyName);
                validated.plan.policyFingerprint.requireSame(
                        PolicyFingerprint.from(chunkPolicy),
                        "after DB2 chunk COMMIT (" + committedRows + " row(s) committed)", 6);
                backfill.requireRootUnchanged(
                        chunkItem, validated.plan.rootFingerprint,
                        "after DB2 chunk COMMIT (" + committedRows + " row(s) committed)", 6);
            }
        };
    }

    private static Db2ChunkedBackfill.CommitGuard postAssignmentCommitGuard(
            final CmService cm,
            final BackfillService backfill,
            final ValidatedBackfill validated) {
        return new Db2ChunkedBackfill.CommitGuard() {
            @Override
            public void verify(long committedRows) throws Exception {
                cm.closeQuietly();
                DKItemTypeDefICM chunkItem = cm.requireItemType(validated.itemTypeName);
                String chunkCurrent = CmService.normalizePolicy(
                        chunkItem.getItemTypeRetentionPolicyName());
                if (!samePolicy(validated.policyName, chunkCurrent)) {
                    throw new CliException("ItemType state changed during post-assignment catch-up: "
                            + validated.itemTypeName + " now uses "
                            + CmService.emptyAsDash(chunkCurrent) + " instead of "
                            + validated.policyName + ".", 6);
                }

                DKRetentionPolicyDefICM chunkPolicy = cm.requirePolicyFresh(validated.policyName);
                validated.plan.policyFingerprint.requireSame(
                        PolicyFingerprint.from(chunkPolicy),
                        "during post-assignment catch-up after " + committedRows + " row(s)", 6);
                backfill.requireRootUnchanged(
                        chunkItem, validated.plan.rootFingerprint,
                        "during post-assignment catch-up after " + committedRows + " row(s)", 6);
            }
        };
    }

    private static void finalVerifyAndCatchUp(final CmService cm,
                                              final BackfillService backfill,
                                              final ValidatedBackfill validated) throws Exception {
        cm.closeQuietly();
        DKItemTypeDefICM freshItem = cm.requireItemType(validated.itemTypeName);
        DKRetentionPolicyDefICM freshPolicy = cm.requirePolicyFresh(validated.policyName);
        validated.plan.policyFingerprint.requireSame(
                PolicyFingerprint.from(freshPolicy), "during final verification", 6);

        String persisted = CmService.normalizePolicy(freshItem.getItemTypeRetentionPolicyName());
        long remaining = backfill.remainingMissing(
                freshItem, validated.plan.rootFingerprint, persisted, validated.policyName);

        if (remaining != 0) {
            System.out.println("Post-assignment catch-up");
            System.out.println("  Residual NULL rows : " + remaining);
            System.out.println("  Reason             : rows appeared during the final pre-assign/assign window");

            BackfillWritePlan catchupPlan = backfill.prepareWrite(
                    freshItem, freshPolicy, persisted, validated.policyName, validated.plan);
            BackfillResult catchup;
            if (Db2ChunkedBackfill.shouldUse(backfill, catchupPlan)) {
                catchup = Db2ChunkedBackfill.apply(backfill, catchupPlan,
                        postAssignmentCommitGuard(cm, backfill, validated));
            } else {
                catchup = backfill.apply(catchupPlan);
            }
            System.out.println("  Catch-up committed : " + catchup.updatedRows + " row(s)");

            cm.closeQuietly();
            freshItem = cm.requireItemType(validated.itemTypeName);
            freshPolicy = cm.requirePolicyFresh(validated.policyName);
            validated.plan.policyFingerprint.requireSame(
                    PolicyFingerprint.from(freshPolicy), "after post-assignment catch-up", 6);
            persisted = CmService.normalizePolicy(freshItem.getItemTypeRetentionPolicyName());
            remaining = backfill.remainingMissing(
                    freshItem, validated.plan.rootFingerprint, persisted, validated.policyName);
        }

        if (remaining != 0) {
            throw new CliException("Backfill verification failed: " + remaining
                    + " row(s) still have NULL retention/auto-delete metadata after catch-up.", 6);
        }
        BackfillMain.printVerificationOk(validated.itemTypeName, validated.policyName);
    }

    private static void printTiming(BackfillService backfill,
                                    long preflightNanos,
                                    long dbNanos,
                                    long guardNanos,
                                    long cmNanos,
                                    long verifyNanos,
                                    long totalStarted) {
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
