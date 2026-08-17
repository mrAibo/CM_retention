import com.ibm.mm.sdk.common.DKConstantICM;
import com.ibm.mm.sdk.common.DKException;
import com.ibm.mm.sdk.common.DKItemTypeDefICM;
import com.ibm.mm.sdk.common.DKRetentionPolicyDefICM;

import java.io.Console;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CmCli {
    private final Config config;
    private final CmService service;

    CmCli(Config config, CmService service) {
        this.config = config;
        this.service = service;
    }

    static boolean handleHelpOrVersionWithoutConfig(String[] args) {
        if (args.length == 0) return false;
        if ("version".equals(args[0]) || "--version".equals(args[0])) {
            System.out.println("cm-retention " + CmRetention.VERSION);
            return true;
        }
        if (isHelp(args[0])) {
            if (args.length == 1) printHelp();
            else if ("advanced".equals(args[1]) || "create".equals(args[1])) printCreateHelp();
            else printCommandHelp(args[1]);
            return true;
        }
        for (String arg : args) {
            if (isHelp(arg)) {
                String command = args[0];
                if ("policy".equals(command) && args.length > 1 && "create".equals(args[1])) command = "create";
                if ("itemtype".equals(command) && args.length > 1
                        && ("assign".equals(args[1]) || "unassign".equals(args[1]))) command = args[1];
                printCommandHelp(command);
                return true;
            }
        }
        return false;
    }

    void run(String[] args) throws Exception {
        if (args.length == 0) {
            interactiveMenu();
            return;
        }
        String command = args[0];
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        if ("status".equals(command)) {
            requireNoArgs(tail); status();
        } else if ("doctor".equals(command)) {
            requireNoArgs(tail); doctor();
        } else if ("policies".equals(command)) {
            requireNoArgs(tail); service.printPolicyList();
        } else if ("policy".equals(command)) {
            commandPolicy(tail);
        } else if ("itemtypes".equals(command)) {
            requireNoArgs(tail); service.printItemTypeList();
        } else if ("itemtype".equals(command)) {
            commandItemType(tail);
        } else if ("create".equals(command)) {
            commandCreate(tail);
        } else if ("assign".equals(command)) {
            commandAssign(tail);
        } else if ("unassign".equals(command)) {
            commandUnassign(tail);
        } else if ("delete".equals(command)) {
            commandDelete(tail);
        } else if ("connection".equals(command)) {
            legacyConnection(tail);
        } else {
            throw new CliException("Unknown command: " + command + ". Run: cm-retention help", 2);
        }
    }

    private void commandPolicy(String[] args) throws Exception {
        if (args.length > 0 && isLegacyPolicyAction(args[0])) {
            legacyPolicy(args);
            return;
        }
        if (args.length > 1) throw new CliException("Usage: cm-retention policy [POLICY]", 2);
        String name = args.length == 1 ? args[0] : selectPolicy("Policy", true);
        if (name == null) { System.out.println("Cancelled."); return; }
        service.printPolicy(name);
    }

    private void commandItemType(String[] args) throws Exception {
        if (args.length > 0 && isLegacyItemTypeAction(args[0])) {
            legacyItemType(args);
            return;
        }
        if (args.length > 1) throw new CliException("Usage: cm-retention itemtype [ITEMTYPE]", 2);
        String name = args.length == 1 ? args[0] : selectItemType("Item type", true);
        if (name == null) { System.out.println("Cancelled."); return; }
        service.printItemType(name);
    }

    private void commandCreate(String[] args) throws Exception {
        ParsedArgs parsed = ParsedArgs.parse(args,
                strings("schedule", "commit-count", "max-items", "max-duration"),
                strings("yes", "dry-run", "force-checkin"));
        if (parsed.positional.size() > 2) {
            throw new CliException("Usage: cm-retention create [POLICY] [AGE] [options]", 2);
        }
        String name = parsed.positional.size() >= 1 ? parsed.positional.get(0) : promptRequired("Policy name");
        String ageValue = parsed.positional.size() >= 2 ? parsed.positional.get(1)
                : promptRequired("Expiration (for example 1y)");
        createPolicy(name, PolicySettings.from(parsed, ageValue), parsed);
    }

    private void commandAssign(String[] args) throws Exception {
        ParsedArgs parsed = ParsedArgs.parse(args, strings(), strings("yes", "dry-run"));
        if (parsed.positional.size() > 2) {
            throw new CliException("Usage: cm-retention assign [ITEMTYPE] [POLICY] [--yes|--dry-run]", 2);
        }
        String itemTypeName = parsed.positional.size() >= 1 ? parsed.positional.get(0)
                : selectItemType("Item type", true);
        if (itemTypeName == null) { System.out.println("Cancelled."); return; }
        String policyName = parsed.positional.size() >= 2 ? parsed.positional.get(1)
                : selectPolicy("Retention policy", true);
        if (policyName == null) { System.out.println("Cancelled."); return; }
        assignPolicy(itemTypeName, policyName, parsed);
    }

    private void commandUnassign(String[] args) throws Exception {
        ParsedArgs parsed = ParsedArgs.parse(args, strings(), strings("yes", "dry-run"));
        if (parsed.positional.size() > 1) {
            throw new CliException("Usage: cm-retention unassign [ITEMTYPE] [--yes|--dry-run]", 2);
        }
        String itemTypeName = parsed.positional.size() == 1 ? parsed.positional.get(0)
                : selectItemType("Item type", true);
        if (itemTypeName == null) { System.out.println("Cancelled."); return; }
        unassignPolicy(itemTypeName, parsed);
    }

    private void commandDelete(String[] args) throws Exception {
        ParsedArgs parsed = ParsedArgs.parse(args, strings(), strings("yes", "dry-run"));
        if (parsed.positional.size() > 1) {
            throw new CliException("Usage: cm-retention delete [POLICY] [--yes|--dry-run]", 2);
        }
        String policyName = parsed.positional.size() == 1 ? parsed.positional.get(0)
                : selectPolicy("Policy to delete", true);
        if (policyName == null) { System.out.println("Cancelled."); return; }
        deletePolicy(policyName, parsed);
    }

    private void createPolicy(String name, PolicySettings settings, ParsedArgs args) throws Exception {
        if (name == null || name.trim().isEmpty()) throw new CliException("Policy name must not be empty", 2);
        if (service.policyExists(name)) throw new CliException("Policy already exists: " + name, 5);

        System.out.println("Create AUTO_DELETE policy\n");
        System.out.println("Name         : " + name);
        System.out.println("Expiration   : " + settings.age.human());
        System.out.println("Action       : AUTO_DELETE");
        System.out.println("Schedule     : " + scheduleHuman(settings.schedule));
        System.out.println("Limits       : " + settings.maxItems + " items / " + settings.maxDuration + " min");
        System.out.println("Commit       : every " + settings.commitCount + " items");
        System.out.println("Force checkin: " + (settings.forceCheckin ? "yes" : "no"));
        if (finishDryRun(args)) return;
        if (!writeApproved(args, "Create")) return;
        service.createPolicy(name, settings);
        System.out.println("Created: " + name);
    }

    private void assignPolicy(String itemTypeName, String policyName, ParsedArgs args) throws Exception {
        DKItemTypeDefICM itemType = service.requireItemType(itemTypeName);
        DKRetentionPolicyDefICM policy = service.requirePolicy(policyName);
        String current = CmService.normalizePolicy(itemType.getItemTypeRetentionPolicyName());
        if (policyName.equals(current)) {
            System.out.println("No change: " + itemTypeName + " already uses " + policyName + ".");
            return;
        }
        System.out.println("Assign retention policy\n");
        System.out.println("Item type : " + itemTypeName);
        System.out.println("Current   : " + CmService.emptyAsDash(current));
        System.out.println("New       : " + policyName);
        System.out.println("Expiration: " + CmService.expirationSummary(policy));
        System.out.println("Action    : " + (policy.isExpirationEnabled() ? policy.getExpirationAction() : "-"));
        System.out.println("\nExisting items are not backfilled.");
        if (finishDryRun(args)) return;
        if (!writeApproved(args, "Apply")) return;
        service.assignPolicy(itemTypeName, policyName, current);
        System.out.println("Assigned: " + itemTypeName + " -> " + policyName);
    }

    private void unassignPolicy(String itemTypeName, ParsedArgs args) throws Exception {
        DKItemTypeDefICM itemType = service.requireItemType(itemTypeName);
        String current = CmService.normalizePolicy(itemType.getItemTypeRetentionPolicyName());
        if (current == null) {
            System.out.println("No change: " + itemTypeName + " has no retention policy.");
            return;
        }
        System.out.println("Unassign retention policy\n");
        System.out.println("Item type : " + itemTypeName);
        System.out.println("Current   : " + current);
        System.out.println("New       : -");
        if (finishDryRun(args)) return;
        if (!writeApproved(args, "Remove policy")) return;
        service.unassignPolicy(itemTypeName, current);
        System.out.println("Unassigned: " + current + " from " + itemTypeName);
    }

    private void deletePolicy(String name, ParsedArgs args) throws Exception {
        DKRetentionPolicyDefICM policy = service.requirePolicy(name);
        List<String> usage = service.policyUsage(name);
        if (!usage.isEmpty()) {
            throw new CliException("Policy is assigned to " + usage.size() + " itemtype(s): "
                    + CmService.join(usage, ", ") + ". Unassign it first.", 5);
        }
        System.out.println("Delete retention policy\n");
        System.out.println("Policy     : " + name);
        System.out.println("Expiration : " + CmService.expirationSummary(policy));
        System.out.println("Action     : " + (policy.isExpirationEnabled() ? policy.getExpirationAction() : "-"));
        System.out.println("Usage      : 0 item types");
        System.out.println("\nThis permanently deletes the policy definition.");
        if (finishDryRun(args)) return;
        if (!writeApproved(args, "Delete permanently")) return;
        service.deletePolicy(name);
        System.out.println("Deleted: " + name);
    }

    private void status() throws Exception {
        String datastoreName = service.datastore().datastoreName();
        System.out.println("CM Retention " + CmRetention.VERSION + "\n");
        System.out.println("Configuration");
        System.out.println("  File       : " + config.envFile);
        System.out.println("  Database   : " + config.database);
        System.out.println("  User       : " + config.user + "\n");
        System.out.println("Runtime");
        System.out.println("  Java       : " + System.getProperty("java.version", "unknown"));
        System.out.println("  IBM CM API : " + DKConstantICM.DK_ICM_RELEASE_VERSION + "\n");
        System.out.println("Content Manager");
        System.out.println("  Connection : OK");
        System.out.println("  Datastore  : " + datastoreName);
        System.out.println("  Policies   : " + service.policyCount());
        System.out.println("  Item types : " + service.itemTypeCount() + "\n");
        System.out.println("Status       : OK");
    }

    private void doctor() throws Exception {
        System.out.println("CM Retention " + CmRetention.VERSION + " doctor\n");
        checkLine(Files.isRegularFile(config.envFile), "configuration file", config.envFile.toString());
        checkLine(Files.isReadable(config.envFile), "configuration readable", config.envFile.toString());
        checkLine(true, "Java runtime", System.getProperty("java.version", "unknown"));
        checkLine(true, "IBM CM API loaded", String.valueOf(DKConstantICM.DK_ICM_RELEASE_VERSION));
        checkLine(service.datastore().isConnected(), "CM login",
                service.datastore().datastoreName() + " / " + service.datastore().userName());
        checkLine(true, "policy API", service.policyCount() + " policies");
        checkLine(true, "itemtype API", service.itemTypeCount() + " item types");
        System.out.println("\nDoctor: OK");
    }

    private static void checkLine(boolean ok, String label, String detail) {
        System.out.println((ok ? "[OK]   " : "[FAIL] ") + padRight(label, 24) + detail);
        if (!ok) throw new CliException("Doctor check failed: " + label, 3);
    }

    private void legacyConnection(String[] args) throws Exception {
        if (args.length != 1 || !"test".equals(args[0])) {
            throw new CliException("Usage: cm-retention connection test", 2);
        }
        warnLegacy("connection test", "status");
        service.connectionTest();
    }

    private void legacyItemType(String[] args) throws Exception {
        String action = args[0];
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        if ("list".equals(action)) {
            requireNoArgs(tail); warnLegacy("itemtype list", "itemtypes"); service.printItemTypeList();
        } else if ("show".equals(action)) {
            requireArgCount(tail, 1, "itemtype show <ITEMTYPE>");
            warnLegacy("itemtype show", "itemtype <ITEMTYPE>"); service.printItemType(tail[0]);
        } else if ("assign".equals(action)) {
            warnLegacy("itemtype assign", "assign <ITEMTYPE> <POLICY>"); commandAssign(tail);
        } else if ("unassign".equals(action)) {
            warnLegacy("itemtype unassign", "unassign <ITEMTYPE>"); commandUnassign(tail);
        } else {
            throw new CliException("Unknown legacy itemtype action: " + action, 2);
        }
    }

    private void legacyPolicy(String[] args) throws Exception {
        String action = args[0];
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        if ("list".equals(action)) {
            requireNoArgs(tail); warnLegacy("policy list", "policies"); service.printPolicyList();
        } else if ("show".equals(action)) {
            requireArgCount(tail, 1, "policy show <POLICY>");
            warnLegacy("policy show", "policy <POLICY>"); service.printPolicy(tail[0]);
        } else if ("usage".equals(action)) {
            requireArgCount(tail, 1, "policy usage <POLICY>");
            warnLegacy("policy usage", "policy <POLICY>"); service.printPolicy(tail[0]);
        } else if ("create".equals(action)) {
            ParsedArgs parsed = ParsedArgs.parse(tail,
                    strings("expiration", "schedule", "commit-count", "max-items", "max-duration"),
                    strings("yes", "dry-run", "force-checkin"));
            if (parsed.positional.size() != 1) {
                throw new CliException("Usage: cm-retention policy create <POLICY> --expiration <AGE> [options]", 2);
            }
            warnLegacy("policy create", "create <POLICY> <AGE>");
            createPolicy(parsed.positional.get(0), PolicySettings.from(parsed, parsed.requiredOption("expiration")), parsed);
        } else if ("delete".equals(action)) {
            warnLegacy("policy delete", "delete <POLICY>"); commandDelete(tail);
        } else {
            throw new CliException("Unknown legacy policy action: " + action, 2);
        }
    }

    private void interactiveMenu() throws Exception {
        requireInteractive("Interactive mode requires a terminal");
        while (true) {
            System.out.println("\nCM Retention " + CmRetention.VERSION + " | " + config.database + " | " + config.user + "\n");
            System.out.println("  1  Policies\n  2  Item types\n  3  Create policy\n  4  Assign policy");
            System.out.println("  5  Unassign policy\n  6  Delete policy\n  7  Status\n  8  Doctor\n\n  q  Quit");
            String choice = console().readLine("Select: ");
            if (choice == null || "q".equalsIgnoreCase(choice.trim())) return;
            try {
                String value = choice.trim();
                if ("1".equals(value)) service.printPolicyList();
                else if ("2".equals(value)) service.printItemTypeList();
                else if ("3".equals(value)) commandCreate(new String[0]);
                else if ("4".equals(value)) commandAssign(new String[0]);
                else if ("5".equals(value)) commandUnassign(new String[0]);
                else if ("6".equals(value)) commandDelete(new String[0]);
                else if ("7".equals(value)) status();
                else if ("8".equals(value)) doctor();
                else if (!value.isEmpty()) System.out.println("Unknown selection: " + value);
            } catch (CliException e) {
                System.err.println("ERROR: " + e.getMessage());
            } catch (OperationWarning e) {
                throw e;
            } catch (DKException e) {
                throw e;
            }
        }
    }

    private String selectItemType(String label, boolean allowCancel) throws Exception {
        requireInteractive("Missing argument and no interactive terminal is available");
        return selectName(label, service.itemTypeNames(), allowCancel);
    }

    private String selectPolicy(String label, boolean allowCancel) throws Exception {
        requireInteractive("Missing argument and no interactive terminal is available");
        return selectName(label, service.policyNames(), allowCancel);
    }

    private String selectName(String label, List<String> names, boolean allowCancel) {
        requireInteractive("Missing argument and no interactive terminal is available");
        if (names.isEmpty()) throw new CliException("No selectable entries found", 4);
        while (true) {
            System.out.println();
            for (int i = 0; i < names.size(); i++) System.out.printf("  %3d  %s%n", i + 1, names.get(i));
            String input = console().readLine(label + ": ");
            if (input == null || input.trim().isEmpty()) {
                if (allowCancel) return null;
                continue;
            }
            String value = input.trim();
            try {
                int index = Integer.parseInt(value);
                if (index >= 1 && index <= names.size()) return names.get(index - 1);
            } catch (NumberFormatException ignored) {
                // exact/prefix matching below
            }
            for (String name : names) {
                if (name.equalsIgnoreCase(value)) {
                    if (!name.equals(value)) System.out.println("Resolved: " + value + " -> " + name);
                    return name;
                }
            }
            List<String> matches = new ArrayList<String>();
            String lower = value.toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (name.toLowerCase(Locale.ROOT).startsWith(lower)) matches.add(name);
            }
            if (matches.size() == 1) {
                System.out.println("Resolved: " + value + " -> " + matches.get(0));
                return matches.get(0);
            }
            if (matches.size() > 1) {
                System.out.println("'" + value + "' matches:");
                for (String match : matches) System.out.println("  - " + match);
            } else {
                System.out.println("No match for '" + value + "'.");
            }
        }
    }

    private String promptRequired(String label) {
        requireInteractive("Missing argument and no interactive terminal is available");
        while (true) {
            String value = console().readLine(label + ": ");
            if (value == null) throw new CliException("Cancelled. No changes made.", 2);
            if (!value.trim().isEmpty()) return value.trim();
        }
    }

    private boolean writeApproved(ParsedArgs args, String verb) {
        if (args.flag("yes")) return true;
        requireInteractive("Write operation refused without --yes when no terminal is attached");
        String answer = console().readLine(verb + "? [y/N]: ");
        if (answer == null || !("y".equalsIgnoreCase(answer.trim()) || "yes".equalsIgnoreCase(answer.trim()))) {
            System.out.println("Cancelled. No changes made.");
            return false;
        }
        return true;
    }

    private static boolean finishDryRun(ParsedArgs args) {
        if (!args.flag("dry-run")) return false;
        System.out.println("\nDRY RUN: validation succeeded. No changes made.");
        return true;
    }

    private static String scheduleHuman(String schedule) {
        return PolicySettings.DEFAULT_SCHEDULE.equals(schedule) ? "daily 02:00 (" + schedule + ")" : schedule;
    }

    private Console console() {
        Console console = System.console();
        if (console == null) throw new CliException("Interactive terminal is not available", 2);
        return console;
    }

    private static void requireInteractive(String message) {
        if (System.console() == null) throw new CliException(message, 2);
    }

    static void printHelp() {
        System.out.println("cm-retention " + CmRetention.VERSION + " - IBM Content Manager 8.7 retention CLI\n");
        System.out.println("Run without arguments for interactive mode.\n");
        System.out.println("Read:");
        System.out.println("  cm-retention status\n  cm-retention policies\n  cm-retention policy [POLICY]");
        System.out.println("  cm-retention itemtypes\n  cm-retention itemtype [ITEMTYPE]\n");
        System.out.println("Write:");
        System.out.println("  cm-retention create [POLICY] [AGE]\n  cm-retention assign [ITEMTYPE] [POLICY]");
        System.out.println("  cm-retention unassign [ITEMTYPE]\n  cm-retention delete [POLICY]\n");
        System.out.println("Diagnostics:\n  cm-retention doctor\n");
        System.out.println("General write flags:");
        System.out.println("  --yes       Skip interactive confirmation (required without a TTY)");
        System.out.println("  --dry-run   Validate and show the plan without changing IBM CM\n");
        System.out.println("Use 'cm-retention create --help' for advanced policy options.");
        System.out.println("Legacy 0.1.x command forms remain accepted with a warning.");
    }

    private static void printCommandHelp(String command) {
        if ("create".equals(command)) printCreateHelp();
        else if ("assign".equals(command)) System.out.println("Usage: cm-retention assign [ITEMTYPE] [POLICY] [--yes|--dry-run]");
        else if ("unassign".equals(command)) System.out.println("Usage: cm-retention unassign [ITEMTYPE] [--yes|--dry-run]");
        else if ("delete".equals(command)) System.out.println("Usage: cm-retention delete [POLICY] [--yes|--dry-run]");
        else if ("policy".equals(command)) System.out.println("Usage: cm-retention policy [POLICY]");
        else if ("itemtype".equals(command)) System.out.println("Usage: cm-retention itemtype [ITEMTYPE]");
        else printHelp();
    }

    private static void printCreateHelp() {
        System.out.println("Usage: cm-retention create [POLICY] [AGE] [options]\n");
        System.out.println("AGE examples: 1y, 12m, 52w, 365d\n");
        System.out.println("Defaults:");
        System.out.println("  schedule       daily 02:00 (0 2 * * *)");
        System.out.println("  commit-count   100\n  max-items      5000 (0 means unlimited)");
        System.out.println("  max-duration   120 minutes\n  force-checkin  false\n");
        System.out.println("Advanced overrides:");
        System.out.println("  --schedule \"0 4 * * *\"\n  --commit-count 200\n  --max-items 10000");
        System.out.println("  --max-duration 180\n  --force-checkin\n");
        System.out.println("Safety:\n  --dry-run   Validate/show plan, make no changes");
        System.out.println("  --yes       Skip confirmation; required for non-interactive writes");
    }

    private static boolean isHelp(String value) {
        return "help".equals(value) || "--help".equals(value) || "-h".equals(value);
    }

    private static boolean isLegacyPolicyAction(String value) {
        return "list".equals(value) || "show".equals(value) || "usage".equals(value)
                || "create".equals(value) || "delete".equals(value);
    }

    private static boolean isLegacyItemTypeAction(String value) {
        return "list".equals(value) || "show".equals(value) || "assign".equals(value) || "unassign".equals(value);
    }

    private static void warnLegacy(String oldForm, String newForm) {
        System.err.println("WARNING: legacy syntax 'cm-retention " + oldForm
                + "'; use 'cm-retention " + newForm + "'.");
    }

    private static void requireNoArgs(String[] args) {
        if (args.length != 0) throw new CliException("Unexpected argument: " + args[0], 2);
    }

    private static void requireArgCount(String[] args, int count, String usage) {
        if (args.length != count) throw new CliException("Usage: cm-retention " + usage, 2);
    }

    private static Set<String> strings(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }

    private static String padRight(String value, int width) {
        StringBuilder builder = new StringBuilder(value);
        while (builder.length() < width) builder.append(' ');
        return builder.toString();
    }
}
