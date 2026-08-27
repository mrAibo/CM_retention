import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_POLICY_TIME_UNIT;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;

/** Pure regression checks: no CM login and no direct database connection are performed. */
public final class SelfTestMain {
    private static int checks;

    private SelfTestMain() { }

    public static void main(String[] args) throws Exception {
        run();
    }

    static void run() throws Exception {
        checks = 0;
        testAgeParsing();
        testTemplateDetection();
        testFingerprints();
        testBackfillSql();
        testBackfillDialects();
        testDb2Chunking();
        testBatchWarningSummary();
        testBatchFinalVerifierMatching();
        testTimingFormat();
        System.out.println("Self-test: OK (" + checks + " checks)");
    }

    private static void testAgeParsing() {
        Age year = Age.parse("1y");
        assertTrue(year.amount == 1, "1y amount");
        assertTrue("YEAR".equals(String.valueOf(year.unit)), "1y unit");
        Age day = Age.parse("365d");
        assertTrue(day.amount == 365, "365d amount");
        assertTrue("DAY".equals(String.valueOf(day.unit)), "365d unit");

        boolean rejected = false;
        try {
            Age.parse("0d");
        } catch (CliException e) {
            rejected = e.exitCode == 2;
        }
        assertTrue(rejected, "zero age rejected");
    }

    private static void testTemplateDetection() throws Exception {
        Path file = Files.createTempFile("cm-retention-selftest-", ".properties");
        try {
            Files.write(file, Arrays.asList(
                    "RET_POLICY_NAME=SELFTEST",
                    "expiration.age=1y"), StandardCharsets.ISO_8859_1);
            String[] normalized = CmRetention.normalizeCreateTemplateArgs(new String[] {
                    "create", "--dry-run", file.toString()
            });
            assertTrue(normalized.length == 4, "template inserts one option");
            assertTrue("create".equals(normalized[0]), "template command preserved");
            assertTrue("--dry-run".equals(normalized[1]), "template flag preserved");
            assertTrue("--properties".equals(normalized[2]), "template detected");
            assertTrue(file.toString().equals(normalized[3]), "template path preserved");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void testFingerprints() {
        PolicyFingerprint expected = policy(1, "YEAR", 120);
        PolicyFingerprint same = policy(1, "YEAR", 120);
        PolicyFingerprint changed = policy(5, "YEAR", 120);
        assertTrue(expected.sameAs(same), "equal policy fingerprints");
        assertTrue(!expected.sameAs(changed), "changed expiration fingerprint");

        boolean mismatchRejected = false;
        try {
            expected.requireSame(changed, "during self-test", 6);
        } catch (CliException e) {
            mismatchRejected = e.exitCode == 6 && e.getMessage().contains("expiration.period");
        }
        assertTrue(mismatchRejected, "policy mismatch exit semantics");

        RootFingerprint root = new RootFingerprint(1238, 1468, 1, "ICMUT01468001");
        RootFingerprint rootSame = new RootFingerprint(1238, 1468, 1, "ICMUT01468001");
        RootFingerprint rootChanged = new RootFingerprint(1238, 1468, 2, "ICMUT01468002");
        assertTrue(root.sameAs(rootSame), "equal root fingerprints");
        assertTrue(!root.sameAs(rootChanged), "changed root fingerprint");
    }

    private static PolicyFingerprint policy(int expirationAmount, String expirationUnit, int maxDuration) {
        return new PolicyFingerprint(
                "AUTO_DELETE_TEST", "FIXED_TIME", false, 0, "YEAR",
                true, expirationAmount, expirationUnit, "AUTO_DELETE",
                "0 2 * * *", 100, 5000, maxDuration, true);
    }

    private static void testBackfillSql() {
        String table = "ICMADMIN.ICMUT01468001";
        BackfillDialect db2 = BackfillDialects.forType("db2");
        String db2Duration = db2.durationSql(1, DK_ICM_POLICY_TIME_UNIT.YEAR);
        String plan = BackfillService.buildPlanSql(
                table, db2Duration, db2.currentTimestampExpression());
        assertTrue(plan.contains("CREATETS + 1 YEAR"), "DB2 plan uses CREATETS");
        assertTrue(plan.contains("CURRENT TIMESTAMP"), "DB2 current timestamp syntax");
        assertTrue(!plan.contains("ICM$CREATETS"), "plan rejects legacy wrong column");
        assertTrue(plan.contains("FROM " + table), "plan table");

        String update = BackfillService.buildUpdateSql(table, db2Duration);
        assertTrue(update.contains("SET ICM$AUTODELETEDATE = CREATETS + 1 YEAR"), "DB2 update formula");
        assertTrue(update.contains("ICM$RETENTIONDATE IS NULL"), "update retention guard");
        assertTrue(update.contains("ICM$AUTODELETEDATE IS NULL"), "update expiration guard");
        assertTrue(update.contains("CREATETS IS NOT NULL"), "update create timestamp guard");
    }

    private static void testBackfillDialects() {
        BackfillDialect db2 = BackfillDialects.forType("db2");
        BackfillDialect oracle = BackfillDialects.forType("oracle");

        assertTrue("db2".equals(BackfillDialects.detectType("jdbc:db2:LSDB")), "detect DB2 JDBC");
        assertTrue("oracle".equals(BackfillDialects.detectType(
                "jdbc:oracle:thin:@//dbhost:1521/LSDB")), "detect Oracle JDBC");

        assertTrue("2 YEARS".equals(db2.durationSql(2, DK_ICM_POLICY_TIME_UNIT.YEAR)),
                "DB2 year duration");
        assertTrue(db2.existsQuery("ICMADMIN.ICMUT01468001", "CREATETS IS NULL")
                .endsWith("FETCH FIRST 1 ROW ONLY"), "DB2 exists syntax");

        assertTrue("INTERVAL '5' YEAR".equals(
                oracle.durationSql(5, DK_ICM_POLICY_TIME_UNIT.YEAR)), "Oracle year duration");
        assertTrue("INTERVAL '300' MONTH(3)".equals(
                oracle.durationSql(300, DK_ICM_POLICY_TIME_UNIT.MONTH)), "Oracle month precision");
        assertTrue("INTERVAL '14' DAY".equals(
                oracle.durationSql(2, DK_ICM_POLICY_TIME_UNIT.WEEK)), "Oracle week duration");
        assertTrue("INTERVAL '364' DAY(3)".equals(
                oracle.durationSql(52, DK_ICM_POLICY_TIME_UNIT.WEEK)), "Oracle week precision");
        assertTrue("INTERVAL '365' DAY(3)".equals(
                oracle.durationSql(365, DK_ICM_POLICY_TIME_UNIT.DAY)), "Oracle day precision");
        assertTrue(oracle.existsQuery("ICMADMIN.ICMUT01468001", "CREATETS IS NULL")
                .contains("ROWNUM = 1"), "Oracle exists syntax");

        String oracleDuration = oracle.durationSql(5, DK_ICM_POLICY_TIME_UNIT.YEAR);
        String oraclePlan = BackfillService.buildPlanSql(
                "ICMADMIN.ICMUT01468001", oracleDuration, oracle.currentTimestampExpression());
        String oracleUpdate = BackfillService.buildUpdateSql(
                "ICMADMIN.ICMUT01468001", oracleDuration);
        assertTrue(oraclePlan.contains("CREATETS + INTERVAL '5' YEAR"),
                "Oracle plan formula");
        assertTrue(oraclePlan.contains("CURRENT_TIMESTAMP"), "Oracle current timestamp syntax");
        assertTrue(oracleUpdate.contains(
                "SET ICM$AUTODELETEDATE = CREATETS + INTERVAL '5' YEAR"),
                "Oracle update formula");

        boolean tooLargeRejected = false;
        try {
            oracle.durationSql(2000000000, DK_ICM_POLICY_TIME_UNIT.WEEK);
        } catch (CliException e) {
            tooLargeRejected = e.exitCode == 5;
        }
        assertTrue(tooLargeRejected, "Oracle interval precision overflow refused");
    }

    private static void testDb2Chunking() {
        String base = BackfillService.buildUpdateSql("ICMADMIN.ICMUT01312001", "1 YEAR");
        String chunk = Db2ChunkedBackfill.buildChunkUpdateSql(base, 250000);
        assertTrue(chunk.endsWith("FETCH FIRST 250000 ROWS ONLY"),
                "DB2 chunk uses searched-update FETCH FIRST");
        assertTrue(Db2ChunkedBackfill.initialChunkRows(7825665L) == 250000,
                "large DB2 backfill starts at bounded chunk size");
        assertTrue(Db2ChunkedBackfill.initialChunkRows(50000L) == 50000,
                "medium DB2 backfill starts at planned size");
        assertTrue(Db2ChunkedBackfill.catchupChunkRows(34L, 250000) == 1000,
                "small concurrent DB2 remainder uses minimum bounded catch-up chunk");
        assertTrue(Db2ChunkedBackfill.catchupChunkRows(82573L, 250000) == 82573,
                "larger concurrent DB2 remainder sizes catch-up to remaining rows");
        assertTrue(Db2ChunkedBackfill.isTransactionLogFull(
                new SQLException("transaction log full", "57011", -964)),
                "SQL0964C recognized for adaptive retry");
        assertTrue(!Db2ChunkedBackfill.isTransactionLogFull(
                new SQLException("other resource", "57011", -999)),
                "generic 57011 is not mistaken for SQL0964C");
    }

    private static void testBatchWarningSummary() {
        assertTrue("AM, CONTRACT ... (+1 more)".equals(
                BatchMain.formatWarningItems(Arrays.asList("AM", "CONTRACT", "INVOICE"), 2)),
                "batch warning list is compact");
        assertTrue("-".equals(BatchMain.formatWarningItems(Arrays.<String>asList(), 20)),
                "empty batch warning list");
    }

    private static void testBatchFinalVerifierMatching() {
        assertTrue(BatchVerifyMain.samePolicy(null, null), "unassigned state matches");
        assertTrue(BatchVerifyMain.samePolicy(null, ""), "blank policy normalizes to unassigned");
        assertTrue(BatchVerifyMain.samePolicy("AUTO_DELETE_1Y", "AUTO_DELETE_1Y"),
                "assigned policy state matches");
        assertTrue(!BatchVerifyMain.samePolicy(null, "AUTO_DELETE_1Y"),
                "assigned policy does not match unassign target");
        assertTrue(!BatchVerifyMain.samePolicy("AUTO_DELETE_1Y", "AUTO_DELETE_5Y"),
                "different policy does not match assign target");
    }

    private static void testTimingFormat() {
        assertTrue("1.000 sec".equals(Timing.format(1000000000L)), "timing unit is seconds");
    }

    private static void assertTrue(boolean condition, String label) {
        checks++;
        if (!condition) {
            throw new IllegalStateException("Self-test failed: " + label);
        }
    }
}
