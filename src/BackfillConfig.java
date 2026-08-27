import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Direct-database settings used only by the explicit --backfill workflow. */
final class BackfillConfig {
    final String jdbcUrl;
    final String user;
    final String password;
    final String schema;
    final BackfillDialect dialect;

    private BackfillConfig(String jdbcUrl,
                           String user,
                           String password,
                           String schema,
                           BackfillDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.schema = schema;
        this.dialect = dialect;
    }

    static BackfillConfig from(Config base) throws IOException {
        Map<String, String> values = load(base);

        String requestedType = configuredValue("BACKFILL_DB_TYPE", values);
        if (requestedType != null && "auto".equalsIgnoreCase(requestedType)) {
            requestedType = null;
        }

        String jdbcUrl = firstConfigured(values,
                "BACKFILL_JDBC_URL",
                "ORACLE_JDBC_URL",
                "DB2_JDBC_URL");

        if (jdbcUrl == null) {
            if (requestedType != null && "oracle".equalsIgnoreCase(requestedType)) {
                throw new CliException("Oracle backfill requires BACKFILL_JDBC_URL"
                        + " (for example jdbc:oracle:thin:@//host:1521/service)", 2);
            }
            String dbName = firstConfigured(values, "BACKFILL_DATABASE", "DB2_DATABASE");
            if (dbName == null) dbName = base.database;
            jdbcUrl = "jdbc:db2:" + dbName;
        }

        String detectedType = BackfillDialects.detectType(jdbcUrl);
        if (detectedType == null) {
            throw new CliException("Unsupported backfill JDBC URL: " + jdbcUrl
                    + " (expected jdbc:db2:... or jdbc:oracle:...)", 2);
        }
        if (requestedType != null && !requestedType.equalsIgnoreCase(detectedType)) {
            throw new CliException("BACKFILL_DB_TYPE=" + requestedType
                    + " conflicts with JDBC URL " + jdbcUrl, 2);
        }

        BackfillDialect dialect = BackfillDialects.forType(detectedType);
        if (!jdbcUrl.toLowerCase(Locale.ROOT).startsWith(dialect.jdbcPrefix())) {
            throw new CliException("Backfill JDBC URL must start with " + dialect.jdbcPrefix(), 2);
        }

        String user;
        String password;
        String schema;
        if ("oracle".equals(dialect.id())) {
            user = firstConfigured(values, "BACKFILL_USER", "ORACLE_USER");
            password = firstConfigured(values, "BACKFILL_PASSWORD", "ORACLE_PASSWORD");
            schema = firstConfigured(values, "BACKFILL_SCHEMA", "ORACLE_SCHEMA");
        } else {
            user = firstConfigured(values, "BACKFILL_USER", "DB2_USER");
            password = firstConfigured(values, "BACKFILL_PASSWORD", "DB2_PASSWORD");
            schema = firstConfigured(values, "BACKFILL_SCHEMA", "DB2_SCHEMA");
        }

        if (user == null) user = base.user;
        if (password == null) password = base.password;
        if (schema == null) schema = "ICMADMIN";
        schema = schema.toUpperCase(Locale.ROOT);

        // IBM CM schemas are normally simple unquoted identifiers. Keep this
        // deliberately strict because schema/table names are inserted into SQL.
        if (!schema.matches("[A-Z][A-Z0-9_$#]*")) {
            throw new CliException("Invalid BACKFILL_SCHEMA: " + schema, 2);
        }

        return new BackfillConfig(jdbcUrl, user, password, schema, dialect);
    }

    private static Map<String, String> load(Config base) throws IOException {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (!Files.isRegularFile(base.envFile)) {
            return values;
        }
        BufferedReader reader = Files.newBufferedReader(base.envFile, StandardCharsets.UTF_8);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int separator = trimmed.indexOf('=');
                if (separator <= 0) continue;
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                if (value.length() >= 2) {
                    char first = value.charAt(0);
                    char last = value.charAt(value.length() - 1);
                    if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                        value = value.substring(1, value.length() - 1);
                    }
                }
                values.put(key, value);
            }
        } finally {
            reader.close();
        }
        return values;
    }

    private static String firstConfigured(Map<String, String> fileValues, String... names) {
        for (String name : names) {
            String value = configuredValue(name, fileValues);
            if (value != null) return value;
        }
        return null;
    }

    private static String configuredValue(String name, Map<String, String> fileValues) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) value = fileValues.get(name);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
