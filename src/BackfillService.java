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
 * Direct DB2 backfill support for existing root-component rows.
 *
 * This class is intentionally separate from CmService: CmService owns IBM CM
 * API mutations, while this service owns the explicit SQL backfill requested
 * through --backfill.
 */
final class BackfillService {
    private final BackfillConfig config;

    BackfillService(BackfillConfig config) {
        this.config = config;
    }

    BackfillPlan plan(DKItemTypeDefICM itemType,
                      DKRetentionPolicyDefICM policy,
                      String currentPolicy,
                      String targetPolicy) throws Exception {
        validatePolicy(policy);
        validateAssignmentState(currentPolicy, targetPolicy);

        Connection connection = connect();
        try {
            RootTable root = resolveRootTable(connection, itemType.getIntId());
            String table = qualified(root.tableName);
            String duration = durationSql(policy);
            String expirationExpression = "CREATETS + " + duration;
            String missing = "ICM$RETENTIONDATE IS NULL AND ICM$AUTODELETEDATE IS NULL";

            long total = queryLong(connection, "SELECT COUNT(*) FROM " + table);
            long missingRows = queryLong(connection,
                    "SELECT COUNT(*) FROM " + table + " WHERE " + missing);
            long fillableRows = queryLong(connection,
                    "SELECT COUNT(*) FROM " + table + " WHERE " + missing
                            + " AND CREATETS IS NOT NULL");
            long missingCreateTs = queryLong(connection,
                    "SELECT COUNT(*) FROM " + table + " WHERE " + missing
                            + " AND CREATETS IS NULL");
            long immediateRows = queryLong(connection,
                    "SELECT COUNT(*) FROM " + table + " WHERE " + missing
                            + " AND CREATETS IS NOT NULL AND "
                            + expirationExpression + " <= CURRENT TIMESTAMP");
            long existingAutoDelete = queryLong(connection,
                    "SELECT COUNT(*) FROM " + table + " WHERE ICM$AUTODELETEDATE IS NOT NULL");
            long retentionSet = queryLong(connection,
                    "SELECT COUNT(*) FROM " + table + " WHERE ICM$RETENTIONDATE IS NOT NULL");

            return new BackfillPlan(
                    itemType.getName(), targetPolicy, CmService.normalizePolicy(currentPolicy),
                    itemType.getIntId(), root.componentTypeId, root.segmentId, root.tableName,
                    policy.getExpirationTimePeriod(), sqlUnit(policy.getDefaultExpirationTimeUnit()),
                    duration, total, missingRows, fillableRows, missingCreateTs,
                    immediateRows, existingAutoDelete, retentionSet);
        } finally {
            closeQuietly(connection);
        }
    }

    BackfillResult apply(BackfillPlan plan) throws Exception {
        if (plan.missingCreateTimestampRows > 0) {
            throw new CliException("Backfill refused: " + plan.missingCreateTimestampRows
                    + " eligible row(s) have NULL CREATETS and cannot be calculated.", 5);
        }
        if (plan.fillableRows == 0) {
            return new BackfillResult(0, 0);
        }

        Connection connection = connect();
        boolean committed = false;
        int updated = 0;
        try {
            connection.setAutoCommit(false);
            String table = qualified(plan.tableName);
            String sql = "UPDATE " + table
                    + " SET ICM$AUTODELETEDATE = CREATETS + " + plan.durationSql
                    + " WHERE ICM$RETENTIONDATE IS NULL"
                    + " AND ICM$AUTODELETEDATE IS NULL"
                    + " AND CREATETS IS NOT NULL";

            Statement statement = connection.createStatement();
            try {
                updated = statement.executeUpdate(sql);
            } finally {
                statement.close();
            }
            connection.commit();
            committed = true;

            long remaining = queryLong(connection,
                    "SELECT COUNT(*) FROM " + table
                            + " WHERE ICM$RETENTIONDATE IS NULL"
                            + " AND ICM$AUTODELETEDATE IS NULL");
            if (remaining != 0) {
                throw new CliException("Backfill committed " + updated + " row(s), but " + remaining
                        + " row(s) still have NULL retention/auto-delete metadata."
                        + " Policy assignment was not started; review concurrent writes and retry.", 6);
            }
            return new BackfillResult(updated, remaining);
        } catch (SQLException e) {
            if (!committed) {
                rollbackQuietly(connection);
                throw e;
            }
            throw new CliException("Backfill DB2 COMMIT completed for " + updated
                    + " row(s), but post-commit verification failed (SQLSTATE "
                    + safeSqlState(e) + "): " + safeSqlMessage(e)
                    + ". Policy assignment was not started; verify DB2 state before retrying.", 6);
        } finally {
            closeQuietly(connection);
        }
    }

    long remainingMissing(DKItemTypeDefICM itemType,
                          DKRetentionPolicyDefICM policy,
                          String currentPolicy,
                          String targetPolicy) throws Exception {
        validatePolicy(policy);
        if (!targetPolicy.equals(CmService.normalizePolicy(currentPolicy))) {
            throw new CliException("Verification failed: itemtype " + itemType.getName()
                    + " uses " + CmService.emptyAsDash(currentPolicy)
                    + " instead of " + targetPolicy, 6);
        }
        Connection connection = connect();
        try {
            RootTable root = resolveRootTable(connection, itemType.getIntId());
            return queryLong(connection,
                    "SELECT COUNT(*) FROM " + qualified(root.tableName)
                            + " WHERE ICM$RETENTIONDATE IS NULL"
                            + " AND ICM$AUTODELETEDATE IS NULL");
        } finally {
            closeQuietly(connection);
        }
    }

    private Connection connect() throws Exception {
        try {
            Class.forName("com.ibm.db2.jcc.DB2Driver");
        } catch (ClassNotFoundException e) {
            throw new CliException("DB2 JDBC driver not found. Add db2jcc4.jar via DB2_JDBC_JAR"
                    + " in the selected .env file or place the driver on the runtime classpath.", 2);
        }
        return DriverManager.getConnection(config.jdbcUrl, config.user, config.password);
    }

    private RootTable resolveRootTable(Connection connection, int itemTypeId) throws SQLException {
        String sql = "SELECT C.COMPONENTTYPEID, I.SEGMENTID"
                + " FROM " + config.schema + ".ICMSTCOMPDEFS C"
                + " JOIN " + config.schema + ".ICMSTITEMTYPEDEFS I"
                + " ON I.ITEMTYPEID=C.ITEMTYPEID"
                + " WHERE C.ITEMTYPEID=? AND C.PARENTCOMPTYPEID=0";
        PreparedStatement statement = connection.prepareStatement(sql);
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
                return new RootTable(componentTypeId, segmentId, tableName);
            } finally {
                rs.close();
            }
        } finally {
            statement.close();
        }
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        Statement statement = connection.createStatement();
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

    private String qualified(String tableName) {
        if (!tableName.matches("ICMUT[0-9]+")) {
            throw new CliException("Unsafe root table name: " + tableName, 5);
        }
        return config.schema + "." + tableName;
    }

    private static void validatePolicy(DKRetentionPolicyDefICM policy) {
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
        sqlUnit(policy.getDefaultExpirationTimeUnit());
    }

    private static void validateAssignmentState(String currentPolicy, String targetPolicy) {
        String current = CmService.normalizePolicy(currentPolicy);
        if (current != null && !targetPolicy.equals(current)) {
            throw new CliException("--backfill refused: itemtype currently uses different policy "
                    + current + ". Unassign/review the existing policy first to avoid mixed dates.", 5);
        }
    }

    private static String durationSql(DKRetentionPolicyDefICM policy) {
        int amount = policy.getExpirationTimePeriod();
        String unit = sqlUnit(policy.getDefaultExpirationTimeUnit());
        return amount + " " + unit + (amount == 1 ? "" : "S");
    }

    private static String sqlUnit(DK_ICM_POLICY_TIME_UNIT unit) {
        if (unit == DK_ICM_POLICY_TIME_UNIT.YEAR) return "YEAR";
        if (unit == DK_ICM_POLICY_TIME_UNIT.MONTH) return "MONTH";
        if (unit == DK_ICM_POLICY_TIME_UNIT.WEEK) return "WEEK";
        if (unit == DK_ICM_POLICY_TIME_UNIT.DAY) return "DAY";
        throw new CliException("Unsupported expiration unit for DB2 backfill: " + unit, 5);
    }

    private static String safeSqlState(SQLException e) {
        return e.getSQLState() == null ? "unknown" : e.getSQLState();
    }

    private static String safeSqlMessage(SQLException e) {
        return e.getMessage() == null || e.getMessage().trim().isEmpty()
                ? e.getClass().getName() : e.getMessage();
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) return;
        try { connection.rollback(); } catch (Exception ignored) { }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (Exception ignored) { }
    }

    private static final class RootTable {
        final int componentTypeId;
        final int segmentId;
        final String tableName;

        RootTable(int componentTypeId, int segmentId, String tableName) {
            this.componentTypeId = componentTypeId;
            this.segmentId = segmentId;
            this.tableName = tableName;
        }
    }
}

final class BackfillPlan {
    final String itemTypeName;
    final String policyName;
    final String currentPolicy;
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
                 int itemTypeId, int componentTypeId, int segmentId, String tableName,
                 int expirationAmount, String expirationUnit, String durationSql,
                 long totalRows, long missingRows, long fillableRows,
                 long missingCreateTimestampRows, long immediatelyExpiredRows,
                 long existingAutoDeleteRows, long retentionDateRows) {
        this.itemTypeName = itemTypeName;
        this.policyName = policyName;
        this.currentPolicy = currentPolicy;
        this.itemTypeId = itemTypeId;
        this.componentTypeId = componentTypeId;
        this.segmentId = segmentId;
        this.tableName = tableName;
        this.expirationAmount = expirationAmount;
        this.expirationUnit = expirationUnit;
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

final class BackfillResult {
    final int updatedRows;
    final long remainingRows;

    BackfillResult(int updatedRows, long remainingRows) {
        this.updatedRows = updatedRows;
        this.remainingRows = remainingRows;
    }
}
