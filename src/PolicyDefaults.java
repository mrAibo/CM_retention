import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** Policy defaults loaded from ret-policy.properties or an explicit override file. */
final class PolicyDefaults {
    static final String DEFAULT_POLICY_NAME = "AUTO_DELETE_1Y";
    static final String DEFAULT_EXPIRATION_AGE = "1y";
    static final String DEFAULT_SCHEDULE = "0 2 * * *";
    static final int DEFAULT_COMMIT_COUNT = 100;
    static final int DEFAULT_MAX_ITEMS = 5000;
    static final int DEFAULT_MAX_DURATION = 120;
    static final boolean DEFAULT_FORCE_CHECKIN = true;

    private static final Set<String> ALLOWED_KEYS = new HashSet<String>(Arrays.asList(
            "RET_POLICY_NAME",
            "retention.type",
            "retention.enabled",
            "expiration.enabled",
            "expiration.age",
            "expiration.action",
            "auto-delete.schedule",
            "auto-delete.commit-count",
            "auto-delete.max-items",
            "auto-delete.max-duration",
            "auto-delete.force-checkin"
    ));

    final String source;
    final String policyName;
    final String expirationAge;
    final String schedule;
    final int commitCount;
    final int maxItems;
    final int maxDuration;
    final boolean forceCheckin;

    private PolicyDefaults(String source, String policyName, String expirationAge, String schedule,
                           int commitCount, int maxItems, int maxDuration,
                           boolean forceCheckin) {
        this.source = source;
        this.policyName = policyName;
        this.expirationAge = expirationAge;
        this.schedule = schedule;
        this.commitCount = commitCount;
        this.maxItems = maxItems;
        this.maxDuration = maxDuration;
        this.forceCheckin = forceCheckin;
    }

    static PolicyDefaults load(String explicitPath) {
        Properties properties = builtinProperties();
        String source = "built-in defaults";

        String configuredPath = explicitPath;
        boolean explicit = configuredPath != null && !configuredPath.trim().isEmpty();
        if (!explicit) {
            configuredPath = System.getenv("CM_RETENTION_POLICY_PROPERTIES");
        }
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            configuredPath = defaultBundledPath();
        }

        if (configuredPath != null && !configuredPath.trim().isEmpty()) {
            Path path = Paths.get(configuredPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                if (explicit || System.getenv("CM_RETENTION_POLICY_PROPERTIES") != null) {
                    throw new CliException("Policy properties file is not readable: " + path, 2);
                }
            } else {
                Properties loaded = new Properties();
                try {
                    InputStream input = Files.newInputStream(path);
                    try {
                        loaded.load(input);
                    } finally {
                        input.close();
                    }
                } catch (IOException e) {
                    throw new CliException("Cannot read policy properties file " + path + ": " + e.getMessage(), 2);
                }
                for (String key : loaded.stringPropertyNames()) {
                    if (!ALLOWED_KEYS.contains(key)) {
                        throw new CliException("Unknown policy property '" + key + "' in " + path, 2);
                    }
                    properties.setProperty(key, loaded.getProperty(key));
                }
                source = path.toString();
            }
        }

        validateSemanticMode(properties, source);

        String policyName = required(properties, "RET_POLICY_NAME", source);
        String expirationAge = required(properties, "expiration.age", source);
        Age.parse(expirationAge);
        String schedule = required(properties, "auto-delete.schedule", source);
        int commitCount = positiveInt(required(properties, "auto-delete.commit-count", source),
                "auto-delete.commit-count", source);
        int maxItems = nonNegativeInt(required(properties, "auto-delete.max-items", source),
                "auto-delete.max-items", source);
        int maxDuration = positiveInt(required(properties, "auto-delete.max-duration", source),
                "auto-delete.max-duration", source);
        boolean forceCheckin = booleanValue(required(properties, "auto-delete.force-checkin", source),
                "auto-delete.force-checkin", source);

        return new PolicyDefaults(source, policyName, expirationAge, schedule,
                commitCount, maxItems, maxDuration, forceCheckin);
    }

    private static String defaultBundledPath() {
        String envFile = System.getenv("CM_RETENTION_ENV_FILE");
        if (envFile != null && !envFile.trim().isEmpty()) {
            Path envPath = Paths.get(envFile).toAbsolutePath().normalize();
            Path parent = envPath.getParent();
            if (parent != null) {
                return parent.resolve("ret-policy.properties").toString();
            }
        }
        return Paths.get("ret-policy.properties").toAbsolutePath().normalize().toString();
    }

    private static Properties builtinProperties() {
        Properties properties = new Properties();
        properties.setProperty("RET_POLICY_NAME", DEFAULT_POLICY_NAME);
        properties.setProperty("retention.type", "FIXED_TIME");
        properties.setProperty("retention.enabled", "false");
        properties.setProperty("expiration.enabled", "true");
        properties.setProperty("expiration.age", DEFAULT_EXPIRATION_AGE);
        properties.setProperty("expiration.action", "AUTO_DELETE");
        properties.setProperty("auto-delete.schedule", DEFAULT_SCHEDULE);
        properties.setProperty("auto-delete.commit-count", String.valueOf(DEFAULT_COMMIT_COUNT));
        properties.setProperty("auto-delete.max-items", String.valueOf(DEFAULT_MAX_ITEMS));
        properties.setProperty("auto-delete.max-duration", String.valueOf(DEFAULT_MAX_DURATION));
        properties.setProperty("auto-delete.force-checkin", String.valueOf(DEFAULT_FORCE_CHECKIN));
        return properties;
    }

    private static void validateSemanticMode(Properties properties, String source) {
        String retentionType = required(properties, "retention.type", source);
        boolean retentionEnabled = booleanValue(required(properties, "retention.enabled", source),
                "retention.enabled", source);
        boolean expirationEnabled = booleanValue(required(properties, "expiration.enabled", source),
                "expiration.enabled", source);
        String expirationAction = required(properties, "expiration.action", source);

        if (!"FIXED_TIME".equalsIgnoreCase(retentionType)) {
            throw new CliException("Unsupported retention.type in " + source
                    + ": " + retentionType + " (only FIXED_TIME is supported)", 2);
        }
        if (retentionEnabled) {
            throw new CliException("Unsupported retention.enabled=true in " + source
                    + "; cm-retention currently creates policies with retention disabled", 2);
        }
        if (!expirationEnabled) {
            throw new CliException("Unsupported expiration.enabled=false in " + source, 2);
        }
        if (!"AUTO_DELETE".equalsIgnoreCase(expirationAction)) {
            throw new CliException("Unsupported expiration.action in " + source
                    + ": " + expirationAction + " (only AUTO_DELETE is supported)", 2);
        }
    }

    private static String required(Properties properties, String key, String source) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new CliException("Missing policy property '" + key + "' in " + source, 2);
        }
        return value.trim();
    }

    private static boolean booleanValue(String value, String key, String source) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new CliException("Invalid boolean for policy property '" + key + "' in "
                + source + ": " + value, 2);
    }

    private static int positiveInt(String value, String key, String source) {
        int parsed = integer(value, key, source);
        if (parsed <= 0) {
            throw new CliException("Policy property '" + key + "' must be greater than zero in " + source, 2);
        }
        return parsed;
    }

    private static int nonNegativeInt(String value, String key, String source) {
        int parsed = integer(value, key, source);
        if (parsed < 0) {
            throw new CliException("Policy property '" + key + "' must not be negative in " + source, 2);
        }
        return parsed;
    }

    private static int integer(String value, String key, String source) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CliException("Invalid integer for policy property '" + key + "' in "
                    + source + ": " + value, 2);
        }
    }
}
