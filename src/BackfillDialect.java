import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_POLICY_TIME_UNIT;

/** Database-specific SQL/JDBC details for the direct existing-item backfill. */
interface BackfillDialect {
    String id();
    String displayName();
    String jdbcPrefix();
    String[] driverClassNames();
    String driverJarHint();
    String expirationExpression(String timestampColumn, int amount, DK_ICM_POLICY_TIME_UNIT unit);
    String currentTimestampExpression();
    String existsQuery(String qualifiedTable, String condition);
}

final class BackfillDialects {
    private BackfillDialects() { }

    static BackfillDialect forType(String value) {
        if (value == null) {
            throw new CliException("Backfill database type is not configured", 2);
        }
        if ("db2".equalsIgnoreCase(value)) return new Db2BackfillDialect();
        if ("oracle".equalsIgnoreCase(value)) return new OracleBackfillDialect();
        throw new CliException("Unsupported BACKFILL_DB_TYPE: " + value
                + " (supported: db2, oracle, auto)", 2);
    }

    static String detectType(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        String value = jdbcUrl.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("jdbc:db2:")) return "db2";
        if (value.startsWith("jdbc:oracle:")) return "oracle";
        return null;
    }
}

final class Db2BackfillDialect implements BackfillDialect {
    @Override
    public String id() { return "db2"; }

    @Override
    public String displayName() { return "DB2"; }

    @Override
    public String jdbcPrefix() { return "jdbc:db2:"; }

    @Override
    public String[] driverClassNames() {
        return new String[] { "com.ibm.db2.jcc.DB2Driver" };
    }

    @Override
    public String driverJarHint() { return "db2jcc4.jar"; }

    @Override
    public String expirationExpression(String timestampColumn,
                                       int amount,
                                       DK_ICM_POLICY_TIME_UNIT unit) {
        String sqlUnit = unit(unit);
        return timestampColumn + " + " + amount + " " + sqlUnit + (amount == 1 ? "" : "S");
    }

    @Override
    public String currentTimestampExpression() { return "CURRENT TIMESTAMP"; }

    @Override
    public String existsQuery(String qualifiedTable, String condition) {
        return "SELECT 1 FROM " + qualifiedTable + " WHERE " + condition
                + " FETCH FIRST 1 ROW ONLY";
    }

    private static String unit(DK_ICM_POLICY_TIME_UNIT unit) {
        if (unit == DK_ICM_POLICY_TIME_UNIT.YEAR) return "YEAR";
        if (unit == DK_ICM_POLICY_TIME_UNIT.MONTH) return "MONTH";
        if (unit == DK_ICM_POLICY_TIME_UNIT.WEEK) return "WEEK";
        if (unit == DK_ICM_POLICY_TIME_UNIT.DAY) return "DAY";
        throw new CliException("Unsupported expiration unit for DB2 backfill: " + unit, 5);
    }
}

final class OracleBackfillDialect implements BackfillDialect {
    @Override
    public String id() { return "oracle"; }

    @Override
    public String displayName() { return "Oracle"; }

    @Override
    public String jdbcPrefix() { return "jdbc:oracle:"; }

    @Override
    public String[] driverClassNames() {
        // ojdbc8 exposes oracle.jdbc.OracleDriver; the legacy class name is kept
        // as a compatibility fallback for older Oracle JDBC installations.
        return new String[] { "oracle.jdbc.OracleDriver", "oracle.jdbc.driver.OracleDriver" };
    }

    @Override
    public String driverJarHint() { return "ojdbc8.jar"; }

    @Override
    public String expirationExpression(String timestampColumn,
                                       int amount,
                                       DK_ICM_POLICY_TIME_UNIT unit) {
        if (unit == DK_ICM_POLICY_TIME_UNIT.YEAR) {
            return timestampColumn + " + NUMTOYMINTERVAL(" + amount + ", 'YEAR')";
        }
        if (unit == DK_ICM_POLICY_TIME_UNIT.MONTH) {
            return timestampColumn + " + NUMTOYMINTERVAL(" + amount + ", 'MONTH')";
        }
        if (unit == DK_ICM_POLICY_TIME_UNIT.WEEK) {
            long days = ((long) amount) * 7L;
            return timestampColumn + " + NUMTODSINTERVAL(" + days + ", 'DAY')";
        }
        if (unit == DK_ICM_POLICY_TIME_UNIT.DAY) {
            return timestampColumn + " + NUMTODSINTERVAL(" + amount + ", 'DAY')";
        }
        throw new CliException("Unsupported expiration unit for Oracle backfill: " + unit, 5);
    }

    @Override
    public String currentTimestampExpression() { return "CURRENT_TIMESTAMP"; }

    @Override
    public String existsQuery(String qualifiedTable, String condition) {
        // ROWNUM keeps this compatible with Oracle versions before FETCH FIRST
        // and is valid for the Oracle 19c level supported by CM 8.7.
        return "SELECT 1 FROM " + qualifiedTable + " WHERE " + condition + " AND ROWNUM = 1";
    }
}
