import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** DB2 settings used only by the explicit --backfill workflow. */
final class BackfillConfig {
    final String jdbcUrl;
    final String user;
    final String password;
    final String schema;

    private BackfillConfig(String jdbcUrl, String user, String password, String schema) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.schema = schema;
    }

    static BackfillConfig from(Config base) throws IOException {
        Map<String, String> values = load(base);

        String dbName = optionalValue("DB2_DATABASE", values, base.database);
        String jdbcUrl = optionalValue("DB2_JDBC_URL", values, "jdbc:db2:" + dbName);
        String user = optionalValue("DB2_USER", values, base.user);
        String password = optionalValue("DB2_PASSWORD", values, base.password);
        String schema = optionalValue("DB2_SCHEMA", values, "ICMADMIN").toUpperCase(Locale.ROOT);

        if (!schema.matches("[A-Z][A-Z0-9_]*")) {
            throw new CliException("Invalid DB2_SCHEMA: " + schema, 2);
        }
        if (!jdbcUrl.startsWith("jdbc:db2:")) {
            throw new CliException("DB2_JDBC_URL must start with jdbc:db2:", 2);
        }
        return new BackfillConfig(jdbcUrl, user, password, schema);
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

    private static String optionalValue(String name, Map<String, String> fileValues, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) value = fileValues.get(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
