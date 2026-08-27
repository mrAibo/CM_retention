import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Pure regression checks: no CM login and no DB2 connection are performed. */
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
        String plan = BackfillService.buildPlanSql(table, "1 YEAR");
        assertTrue(plan.contains("CREATETS + 1 YEAR"), "plan uses CREATETS");
        assertTrue(!plan.contains("ICM$CREATETS"), "plan rejects legacy wrong column");
        assertTrue(plan.contains("FROM " + table), "plan table");

        String update = BackfillService.buildUpdateSql(table, "1 YEAR");
        assertTrue(update.contains("SET ICM$AUTODELETEDATE = CREATETS + 1 YEAR"), "update formula");
        assertTrue(update.contains("ICM$RETENTIONDATE IS NULL"), "update retention guard");
        assertTrue(update.contains("ICM$AUTODELETEDATE IS NULL"), "update expiration guard");
        assertTrue(update.contains("CREATETS IS NOT NULL"), "update create timestamp guard");
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
