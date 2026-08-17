import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

final class Config {
    final Path envFile;
    final String database;
    final String user;
    final String password;

    private Config(Path envFile, String database, String user, String password) {
        this.envFile = envFile;
        this.database = database;
        this.user = user;
        this.password = password;
    }

    static Config fromEnvironment() throws IOException {
        String configuredPath = System.getenv("CM_RETENTION_ENV_FILE");
        Path path = configuredPath == null || configuredPath.trim().isEmpty()
                ? Paths.get(".env") : Paths.get(configuredPath);
        path = path.toAbsolutePath().normalize();

        Map<String, String> fileValues = loadEnvFile(path);
        return new Config(
                path,
                requiredValue("CM_DATABASE", fileValues),
                requiredValue("CM_USER", fileValues),
                requiredValue("CM_PASSWORD", fileValues));
    }

    private static Map<String, String> loadEnvFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new CliException("Configuration file not found: " + path, 2);
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        try {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    throw new CliException("Invalid .env line " + lineNumber + ": expected KEY=VALUE", 2);
                }
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new CliException("Invalid .env key on line " + lineNumber + ": " + key, 2);
                }
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

    private static String requiredValue(String name, Map<String, String> fileValues) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            value = fileValues.get(name);
        }
        if (value == null || value.trim().isEmpty()) {
            throw new CliException("Missing configuration: " + name, 2);
        }
        return value;
    }
}
