import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM.DK_ICM_POLICY_TIME_UNIT;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ParsedArgs {
    final List<String> positional = new ArrayList<String>();
    final Map<String, String> options = new LinkedHashMap<String, String>();
    final Set<String> flags = new HashSet<String>();

    static ParsedArgs parse(String[] args, Set<String> allowedOptions, Set<String> allowedFlags) {
        ParsedArgs parsed = new ParsedArgs();
        for (int i = 0; i < args.length; i++) {
            String current = args[i];
            if (!current.startsWith("--")) {
                parsed.positional.add(current);
                continue;
            }
            String key = current.substring(2);
            if (key.isEmpty()) {
                throw new CliException("Invalid option: " + current, 2);
            }
            if (allowedFlags.contains(key)) {
                if (!parsed.flags.add(key)) {
                    throw new CliException("Duplicate flag: --" + key, 2);
                }
                continue;
            }
            if (!allowedOptions.contains(key)) {
                throw new CliException("Unknown option '--" + key + "'", 2);
            }
            if (parsed.options.containsKey(key)) {
                throw new CliException("Duplicate option: --" + key, 2);
            }
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                throw new CliException("Missing value for --" + key, 2);
            }
            parsed.options.put(key, args[++i]);
        }
        return parsed;
    }

    String requiredOption(String name) {
        String value = options.get(name);
        if (value == null || value.trim().isEmpty()) {
            throw new CliException("Missing required option: --" + name, 2);
        }
        return value;
    }

    String option(String name, String defaultValue) {
        String value = options.get(name);
        return value == null ? defaultValue : value;
    }

    boolean flag(String name) {
        return flags.contains(name);
    }
}

final class PolicySettings {
    static final String DEFAULT_SCHEDULE = "0 2 * * *";
    static final int DEFAULT_COMMIT_COUNT = 100;
    static final int DEFAULT_MAX_ITEMS = 5000;
    static final int DEFAULT_MAX_DURATION = 120;

    final Age age;
    final String schedule;
    final int commitCount;
    final int maxItems;
    final int maxDuration;
    final boolean forceCheckin;

    PolicySettings(Age age, String schedule, int commitCount, int maxItems,
                   int maxDuration, boolean forceCheckin) {
        this.age = age;
        this.schedule = schedule;
        this.commitCount = commitCount;
        this.maxItems = maxItems;
        this.maxDuration = maxDuration;
        this.forceCheckin = forceCheckin;
    }

    static PolicySettings from(ParsedArgs args, String ageValue) {
        Age age = Age.parse(ageValue);
        String schedule = args.option("schedule", DEFAULT_SCHEDULE);
        if (schedule.trim().isEmpty()) {
            throw new CliException("--schedule must not be empty", 2);
        }
        return new PolicySettings(
                age,
                schedule,
                positiveInt(args.option("commit-count", String.valueOf(DEFAULT_COMMIT_COUNT)), "commit-count"),
                nonNegativeInt(args.option("max-items", String.valueOf(DEFAULT_MAX_ITEMS)), "max-items"),
                positiveInt(args.option("max-duration", String.valueOf(DEFAULT_MAX_DURATION)), "max-duration"),
                args.flag("force-checkin"));
    }

    private static int positiveInt(String value, String option) {
        int parsed = parseInt(value, option);
        if (parsed <= 0) {
            throw new CliException("--" + option + " must be greater than zero", 2);
        }
        return parsed;
    }

    private static int nonNegativeInt(String value, String option) {
        int parsed = parseInt(value, option);
        if (parsed < 0) {
            throw new CliException("--" + option + " must not be negative", 2);
        }
        return parsed;
    }

    private static int parseInt(String value, String option) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new CliException("Invalid integer for --" + option + ": " + value, 2);
        }
    }
}

final class Age {
    final int amount;
    final DK_ICM_POLICY_TIME_UNIT unit;

    Age(int amount, DK_ICM_POLICY_TIME_UNIT unit) {
        this.amount = amount;
        this.unit = unit;
    }

    static Age parse(String value) {
        if (value == null) {
            throw new CliException("Expiration age is missing", 2);
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[1-9][0-9]*[ymwd]")) {
            throw new CliException("Invalid age '" + value
                    + "'. Expected for example: 1y, 12m, 52w or 365d", 2);
        }
        int amount;
        try {
            amount = Integer.parseInt(normalized.substring(0, normalized.length() - 1));
        } catch (NumberFormatException e) {
            throw new CliException("Invalid age: " + value, 2);
        }
        switch (normalized.charAt(normalized.length() - 1)) {
            case 'y': return new Age(amount, DK_ICM_POLICY_TIME_UNIT.YEAR);
            case 'm': return new Age(amount, DK_ICM_POLICY_TIME_UNIT.MONTH);
            case 'w': return new Age(amount, DK_ICM_POLICY_TIME_UNIT.WEEK);
            case 'd': return new Age(amount, DK_ICM_POLICY_TIME_UNIT.DAY);
            default: throw new CliException("Unsupported age: " + value, 2);
        }
    }

    String human() {
        String unitName = String.valueOf(unit).toLowerCase(Locale.ROOT);
        return amount + " " + unitName + (amount == 1 ? "" : "s");
    }
}

final class OperationWarning extends Exception {
    private static final long serialVersionUID = 1L;
    final DKException cause;

    OperationWarning(String message, DKException cause) {
        super(message, cause);
        this.cause = cause;
    }
}

final class CliException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int exitCode;

    CliException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }
}
