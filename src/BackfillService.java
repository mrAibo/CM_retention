import com.ibm.mm.sdk.common.DKItemTypeDefICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_EXPIRATION_ACTION_TYPE;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_POLICY_TIME_UNIT;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_RETENTION_TYPE;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Direct database backfill support for existing root-component rows.
 *
 * One BackfillService instance lazily opens one JDBC connection and reuses it
 * for its lifetime. Database-specific timestamp arithmetic, current-timestamp
 * syntax, one-row probing and JDBC driver loading are delegated to the selected
 * BackfillDialect (DB2 or Oracle).
 */
final class BackfillService {
    private static final String MISSING_DATES =
            "ICM$RETENTIONDATE IS NULL AND ICM$AUTODELETEDATE IS NULL";

    private final BackfillConfig config;
    private final BackfillDialect dialect;
    private Connection connection;

    BackfillService(BackfillConfig config) {
        this.config = config;
        this.dialect = config.dialect;
    }

    String databaseDisplayName() {
        return dialect.displayName();
    }

    /** Package-private primitives used only by the DB2 bounded-chunk executor. */
    Connection directConnection() throws Exception {
        return connection();
    }

    String qualifiedTable(String tableName) {
        return qualified(tableName);
    }

    void restoreConnectionState(Connection db, boolean originalAutoCommit) {
        restoreAutoCommitOrReconnect(db, originalAutoCommit);
    }

    /** Detailed, read-only plan used by dry-run/Phase 1. */
    BackfillPlan plan(DKItemTypeDefICM itemType,
                      DKRetentionPolicyDefICM policy,
                      String currentPolicy,
                      String targetPolicy) throws Exception {
        validatePolicy(policy);
        validateAssignmentState(currentPolicy, targetPolicy);

        Connection db = connection();
        RootFingerprint root = resolveRootFingerprint(db, itemType.getIntId());
        String table = qualified(root.tableName);
        String duration = durationSql(policy);
        long[] counts = queryCounts(db,
                buildPlanSql(table, duration, dialect.currentTimestampExpression()), 7);

        return new BackfillPlan(
                itemType.getName(), targetPolicy, CmService.normalizePolicy(currentPolicy),
                root, PolicyFingerprint.from(policy), duration,
                counts[0], counts[1], counts[2], counts[3],
                counts[4], counts[5], counts[6]);
    }

    /**
     * Cheap stale-plan check immediately before UPDATE.
     *
     * It intentionally avoids rebuilding the detailed aggregate plan. The only
     * root-data query checks whether a currently eligible row has NULL CREATETS;
     * root identity and policy semantics are compared against Phase 1.
     */
    BackfillWritePlan prepareWrite(DKItemTypeDefICM itemType,
                                   DKRetentionPolicyDefICM policy,
                                   String currentPolicy,
                                   String targetPolicy,
                                   BackfillPlan validated) throws Exception {
        if (validated == null) {
            throw new CliException("Internal backfill error: missing validated plan", 2);
        }
        validatePolicy(policy);
        validateAssignmentState(currentPolicy, targetPolicy);

        if (!validated.itemTypeName.equals(itemType.getName())
                || !validated.policyName.equals(targetPolicy)) {
            throw new CliException("Backfill target changed after validation. Re-run the operation.", 5);
        }

        PolicyFingerprint currentPolicyFingerprint = PolicyFingerprint.from(policy);
        validated.policyFingerprint.requireSame(
                currentPolicyFingerprint, "after validation and before database backfill", 5);

        Connection db = connection();
        RootFingerprint currentRoot = resolveRootFingerprint(db, itemType.getIntId());
        validated.rootFingerprint.requireSame(
                currentRoot, "after validation and before database backfill", 5);
        validateSegment(currentRoot, 5);

        String table = qualified(currentRoot.tableName);
        String missingCreateTimestamp = MISSING_DATES + " AND CREATETS IS NULL";
        if (queryExists(db, dialect.existsQuery(table, missingCreateTimestamp))) {
            throw new CliException("Backfill refused: at least one eligible row has NULL CREATETS.", 5);
        }

        String currentDuration = durationSql(policy);
        if (!validated.durationSql.equals(currentDuration)) {
            throw new CliException("Backfill SQL duration changed after validation. Re-run the operation.", 5);
        }

        return new BackfillWritePlan(
                itemType.getName(), targetPolicy, CmService.normalizePolicy(currentPolicy),
                currentRoot, currentPolicyFingerprint, currentDuration,
                validated.fillableRows);
    }

    BackfillResult apply(BackfillWritePlan plan) throws Exception {
        Connection db = connection();
        boolean committed = false;
        int updated = 0;
        boolean originalAutoCommit = db.getAutoCommit();
        try {
            if (originalAutoCommit) {
                db.setAutoCommit(false);
            }
            String table = qualified(plan.rootFingerprint.tableName);
            String sql = buildUpdateSql(table, plan.durationSql);

            Statement statement = db.createStatement();
            try {
                updated = statement.executeUpdate(sql);
            } finally {
                statement.close();
            }
            db.commit();
            committed = true;

            long remaining = queryLong(db,
                    "SELECT COUNT(*) FROM " + table + " WHERE " + MISSING_DATES);
            if (remaining != 0) {
                throw new CliException("Backfill committed " + updated + " row(s), but " + remaining
                        + " row(s) still have NULL retention/auto-delete metadata."
                        + " Policy assignment was not started; review concurrent writes and retry.", 6);
            }
            return new BackfillResult(updated, remaining);
        } catch (SQLException e) {
            if (!committed) {
                rollbackQuietly(db);
                throw e;
            }
            throw new CliException("Backfill " + dialect.displayName() + " COMMIT completed for " + updated
                    + " row(s), but post-commit verification failed (SQLSTATE "
                    + safeSqlState(e) + "): " + safeSqlMessage(e)
                    + ". Policy assignment was not started; verify database state before retrying.", 6);
        } finally {
            restoreAutoCommitOrReconnect(db, originalAutoCommit);
        }
    }

    long remainingMissing(DKItemTypeDefICM itemType,
                          RootFingerprint expectedRoot,
                          String currentPolicy,
                          String targetPolicy) throws Exception {
        if (!targetPolicy.equals(CmService.normalizePolicy(currentPolicy))) {
            throw new CliException("Verification failed: itemtype " + itemType.getName()
                    + " uses " + CmService.emptyAsDash(currentPolicy)
                    + " instead of " + targetPolicy, 6);
        }
        Connection db = connection();
        RootFingerprint currentRoot = resolveRootFingerprint(db, itemType.getIntId());
        expectedRoot.requireSame(currentRoot, "during final verification", 6);
        validateSegment(currentRoot, 6);
        return queryLong(db,
                "SELECT COUNT(*) FROM " + qualified(currentRoot.tableName)
                        + " WHERE " + MISSING_DATES);
    }

    /**
     * Independent batch-verifier path. It derives the current root from fresh CM
     * metadata and first uses a one-row existence probe so clean ItemTypes avoid
     * a full COUNT. A COUNT is done only when a residual mismatch actually exists.
     */
    long remainingMissingForIndependentVerification(DKItemTypeDefICM itemType,
                                                     String currentPolicy,
                                                     String targetPolicy) throws Exception {
        if (!targetPolicy.equals(CmService.normalizePolicy(currentPolicy))) {
            throw new CliException("Verification failed: itemtype " + itemType.getName()
                    + " uses " + CmService.emptyAsDash(currentPolicy)
                    + " instead of " + targetPolicy, 6);
        }
        Connection db = connection();
        RootFingerprint currentRoot = resolveRootFingerprint(db, itemType.getIntId());
        validateSegment(currentRoot, 6);
        String table = qualified(currentRoot.tableName);
        if (!queryExists(db, dialect.existsQuery(table, MISSING_DATES))) {
            return 0L;
        }
        return queryLong(db,
                "SELECT COUNT(*) FROM " + table + " WHERE " + MISSING_DATES);
    }

    void requireRootUnchanged(DKItemTypeDefICM itemType,
                              RootFingerprint expectedRoot,
                              String stage,
                              int exitCode) throws Exception {
        RootFingerprint currentRoot = resolveRootFingerprint(connection(), itemType.getIntId());
        expectedRoot.requireSame(currentRoot, stage, exitCode);
        validateSegment(currentRoot, exitCode);
    }

    void closeQuietly() {
        Connection db = connection;
        connection = null;
        if (db == null) return;
        try {
            if (!db.isClosed()) db.close();
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
    }

    static String buildPlanSql(String qualifiedTable,
                               String duration,
                               String currentTimestampExpression) {
        String expirationExpression = "CREATETS + " + duration;
        return "SELECT "
                + "COUNT(*), "
                + "SUM(CASE WHEN " + MISSING_DATES + " THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN " + MISSING_DATES + " AND CREATETS IS NOT NULL THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN " + MISSING_DATES + " AND CREATETS IS NULL THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN " + MISSING_DATES + " AND CREATETS IS NOT NULL AND "
                + expirationExpression + " <= " + currentTimestampExpression + " THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN ICM$AUTODELETEDATE IS NOT NULL THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN ICM$RETENTIONDATE IS NOT NULL THEN 1 ELSE 0 END) "
                + "FROM " + qualifiedTable;
    }

    static String buildUpdateSql(String qualifiedTable, String duration) {
        return "UPDATE " + qualifiedTable
                + " SET ICM$AUTODELETEDATE = CREATETS + " + duration
                + " WHERE ICM$RETENTIONDATE IS NULL"
                + " AND ICM$AUTODELETEDATE IS NULL"
                + " AND CREATETS IS NOT NULL";
    }

    private Connection connection() throws Exception {
        if (connection != null && !connection.isClosed()) {
            return connection;
        }

        boolean loaded = false;
        for (String driverClass : dialect.driverClassNames()) {
            try {
                Class.forName(driverClass);
                loaded = true;
                break;
            } catch (ClassNotFoundException ignored) {
                // Try the next compatible driver class name.
            }
        }
        if (!loaded) {
            throw new CliException(dialect.displayName() + " JDBC driver not found. Add "
                    + dialect.driverJarHint() + " via BACKFILL_JDBC_JAR"
                    + " (legacy DB2_JDBC_JAR / ORACLE_JDBC_JAR are also accepted by the launcher)"
                    + " or place the driver on the runtime classpath.", 2);
        }

        connection = DriverManager.getConnection(config.jdbcUrl, config.user, config.password);
        return connection;
    }

    private RootFingerprint resolveRootFingerprint(Connection db, int itemTypeId) throws SQLException {
        String sql = "SELECT C.COMPONENTTYPEID, I.SEGMENTID"
                + " FROM " + config.schema + ".ICMSTCOMPDEFS C"
                + " JOIN " + config.schema + ".ICMSTITEMTYPEDEFS I"
                + " ON I.ITEMTYPEID=C.ITEMTYPEID"
                + " WHERE C.ITEMTYPEID=? AND C.PARENTCOMPTYPEID=0";
        PreparedStatement statement = db.prepareStatement(sql);
        try {
            statement.setInt(1, itemTypeId);
            ResultSet rs = statement.executeQuery();
            try {
                if (!rs.next()) {
                    throw new CliException("No root component found for itemtype ID " + itemTypeId, 5);
                }
                int componentTypeId = rs.getInt(1);
                int segmentId = rs.getInt(2);
                if (rs.next()) {
                    throw new CliException("Multiple root components found for itemtype ID "
                            + itemTypeId + "; backfill refused.", 5);
                }
                String tableName = String.format(Locale.ROOT,
                        "ICMUT%05d%03d", componentTypeId, segmentId);
                if (!tableName.matches("ICMUT[0-9]+")) {
                    throw new CliException("Unsafe generated root table name: " + tableName, 5);
                }
                return new RootFingerprint(itemTypeId, componentTypeId, segmentId, tableName);
            } finally {
                rs.close();
            }
        } finally {
            statement.close();
        }
    }

    private long[] queryCounts(Connection db, String sql, int columns) throws SQLException {
        Statement statement = db.createStatement();
        try {
            ResultSet rs = statement.executeQuery(sql);
            try {
                if (!rs.next()) throw new SQLException("Aggregate query returned no row");
                long[] values = new long[columns];
                for (int i = 0; i < columns; i++) {
                    values[i] = rs.getLong(i + 1);
                }
                return values;
            } finally {
                rs.close();
            }
        } finally {
            statement.close();
        }
    }

    private long queryLong(Connection db, String sql) throws SQLException {
        Statement statement = db.createStatement();
        try {
            ResultSet rs = statement.executeQuery(sql);
            try {
                if (!rs.next()) throw new SQLException("COUNT query returned no row");
                return rs.getLong(1);
            } finally {
                rs.close();
            }
        } finally {
            statement.close();
        }
    }

    private boolean queryExists(Connection db, String sql) throws SQLException {
        Statement statement = db.createStatement();
        try {
            ResultSet rs = statement.executeQuery(sql);
            try {
                return rs.next();
            } finally {
                rs.close();
            }
        } finally {
            statement.close();
        }
    }

    private String qualified(String tableName) {
        if (!tableName.matches("ICMUT[0-9]+")) {
            throw new CliException("Unsafe root table name: " + tableName, 5);
        }
        return config.schema + "." + tableName;
    }

    private void validatePolicy(DKRetentionPolicyDefICM policy) {
        if (policy.getRetentionType() != DK_ICM_RETENTION_TYPE.FIXED_TIME) {
            throw new CliException("--backfill supports FIXED_TIME policies only", 5);
        }
        if (policy.isRetentionEnabled()) {
            throw new CliException("--backfill currently supports policies with retention disabled only;"
                    + " ICM$RETENTIONDATE would otherwise require separate calculation.", 5);
        }
        if (!policy.isExpirationEnabled()) {
            throw new CliException("--backfill requires expiration to be enabled", 5);
        }
        if (policy.getExpirationAction() != DK_ICM_EXPIRATION_ACTION_TYPE.AUTO_DELETE) {
            throw new CliException("--backfill requires expiration action AUTO_DELETE", 5);
        }
        if (policy.getExpirationTimePeriod() <= 0) {
            throw new CliException("--backfill requires a positive expiration period", 5);
        }
        durationSql(policy);
    }

    private static void validateAssignmentState(String currentPolicy, String targetPolicy) {
        String current = CmService.normalizePolicy(currentPolicy);
        if (current != null && !targetPolicy.equals(current)) {
            throw new CliException("--backfill refused: itemtype currently uses different policy "
                    + current + ". Unassign/review the existing policy first to avoid mixed dates.", 5);
        }
    }

    private static void validateSegment(RootFingerprint root, int exitCode) {
        if (root.segmentId != 1) {
            throw new CliException("Backfill refused: ItemType uses component SegmentID "
                    + root.segmentId + ". Multi-segment backfill is not implemented; refusing"
                    + " to update only one segment.", exitCode);
        }
    }

    private String durationSql(DKRetentionPolicyDefICM policy) {
        int amount = policy.getExpirationTimePeriod();
        DK_ICM_POLICY_TIME_UNIT unit = policy.getDefaultExpirationTimeUnit();
        return dialect.durationSql(amount, unit);
    }

    private static String safeSqlState(SQLException e) {
        return e.getSQLState() == null ? "unknown" : e.getSQLState();
    }

    private static String safeSqlMessage(SQLException e) {
        return e.getMessage() == null || e.getMessage().trim().isEmpty()
                ? e.getClass().getName() : e.getMessage();
    }

    private static void rollbackQuietly(Connection db) {
        if (db == null) return;
        try { db.rollback(); } catch (Exception ignored) { }
    }

    private void restoreAutoCommitOrReconnect(Connection db, boolean originalAutoCommit) {
        if (db == null) return;
        try {
            if (!db.isClosed() && db.getAutoCommit() != originalAutoCommit) {
                db.setAutoCommit(originalAutoCommit);
            }
        } catch (Exception ignored) {
            closeQuietly();
        }
    }
}

final class BackfillPlan {
    final String itemTypeName;
    final String policyName;
    final String currentPolicy;
    final RootFingerprint rootFingerprint;
    final PolicyFingerprint policyFingerprint;
    final int itemTypeId;
    final int componentTypeId;
    final int segmentId;
    final String tableName;
    final int expirationAmount;
    final String expirationUnit;
    final String durationSql;
    final long totalRows;
    final long missingRows;
    final long fillableRows;
    final long missingCreateTimestampRows;
    final long immediatelyExpiredRows;
    final long existingAutoDeleteRows;
    final long retentionDateRows;

    BackfillPlan(String itemTypeName, String policyName, String currentPolicy,
                 RootFingerprint rootFingerprint, PolicyFingerprint policyFingerprint,
                 String durationSql, long totalRows, long missingRows, long fillableRows,
                 long missingCreateTimestampRows, long immediatelyExpiredRows,
                 long existingAutoDeleteRows, long retentionDateRows) {
        this.itemTypeName = itemTypeName;
        this.policyName = policyName;
        this.currentPolicy = currentPolicy;
        this.rootFingerprint = rootFingerprint;
        this.policyFingerprint = policyFingerprint;
        this.itemTypeId = rootFingerprint.itemTypeId;
        this.componentTypeId = rootFingerprint.componentTypeId;
        this.segmentId = rootFingerprint.segmentId;
        this.tableName = rootFingerprint.tableName;
        this.expirationAmount = policyFingerprint.expirationAmount;
        this.expirationUnit = policyFingerprint.expirationUnit;
        this.durationSql = durationSql;
        this.totalRows = totalRows;
        this.missingRows = missingRows;
        this.fillableRows = fillableRows;
        this.missingCreateTimestampRows = missingCreateTimestampRows;
        this.immediatelyExpiredRows = immediatelyExpiredRows;
        this.existingAutoDeleteRows = existingAutoDeleteRows;
        this.retentionDateRows = retentionDateRows;
    }
}

final class BackfillWritePlan {
    final String itemTypeName;
    final String policyName;
    final String currentPolicy;
    final RootFingerprint rootFingerprint;
    final PolicyFingerprint policyFingerprint;
    final String durationSql;
    final long plannedFillableRows;

    BackfillWritePlan(String itemTypeName, String policyName, String currentPolicy,
                      RootFingerprint rootFingerprint, PolicyFingerprint policyFingerprint,
                      String durationSql, long plannedFillableRows) {
        this.itemTypeName = itemTypeName;
        this.policyName = policyName;
        this.currentPolicy = currentPolicy;
        this.rootFingerprint = rootFingerprint;
        this.policyFingerprint = policyFingerprint;
        this.durationSql = durationSql;
        this.plannedFillableRows = plannedFillableRows;
    }
}

final class BackfillResult {
    final int updatedRows;
    final long remainingRows;

    BackfillResult(int updatedRows, long remainingRows) {
        this.updatedRows = updatedRows;
        this.remainingRows = remainingRows;
    }

    boolean changedRows() {
        return updatedRows > 0;
    }
}
