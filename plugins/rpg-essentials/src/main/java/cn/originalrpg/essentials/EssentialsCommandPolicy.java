package cn.originalrpg.essentials;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class EssentialsCommandPolicy {
    private static final Map<String, String> RECOMMENDATIONS = Map.ofEntries(
            Map.entry("back", "/back"), Map.entry("return", "/back"),
            Map.entry("home", "/home"), Map.entry("sethome", "/sethome"),
            Map.entry("delhome", "/sethome"), Map.entry("spawn", "/hub"),
            Map.entry("warp", "/menu"), Map.entry("warps", "/menu"),
            Map.entry("tpr", "/wild"), Map.entry("balance", "/economy"),
            Map.entry("balancetop", "/economy"), Map.entry("baltop", "/economy"),
            Map.entry("money", "/economy"), Map.entry("pay", "/economy transfer"),
            Map.entry("eco", "/economy"), Map.entry("economy", "/economy"),
            Map.entry("sell", "/market"), Map.entry("worth", "/economy shop"),
            Map.entry("kit", "/tasks"), Map.entry("kits", "/tasks"),
            Map.entry("backpack", "/warehouse"),
            Map.entry("repair", "/refine"), Map.entry("enchant", "/refine"),
            Map.entry("nick", "/account"), Map.entry("world", "/menu")
    );

    private final Set<String> blockedAliases;

    EssentialsCommandPolicy(Set<String> blockedAliases) {
        this.blockedAliases = Set.copyOf(blockedAliases);
    }

    Decision inspect(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return Decision.allowed();
        String token = rawMessage.stripLeading().split("\\s+", 2)[0];
        if (token.startsWith("/")) token = token.substring(1);
        String lower = token.toLowerCase(Locale.ROOT);
        int namespace = lower.indexOf(':');
        if (namespace >= 0) {
            String owner = lower.substring(0, namespace);
            String command = lower.substring(namespace + 1);
            if (command.equals("backpack") || owner.equals("essentials") && isRestricted(command)) {
                return Decision.blocked(recommend(command));
            }
            return Decision.allowed();
        }
        if (blockedAliases.contains(lower)) return Decision.blocked(recommend(stripE(lower)));
        return Decision.allowed();
    }

    private boolean isRestricted(String command) {
        String normalized = stripE(command);
        return RECOMMENDATIONS.containsKey(normalized) || blockedAliases.contains(command);
    }

    private String recommend(String command) {
        return RECOMMENDATIONS.getOrDefault(stripE(command), "/menu");
    }

    private static String stripE(String command) {
        if (command.startsWith("e") && command.length() > 1) {
            String without = command.substring(1);
            if (RECOMMENDATIONS.containsKey(without)) return without;
        }
        return command;
    }

    record Decision(boolean blocked, String recommendation) {
        static Decision allowed() { return new Decision(false, ""); }
        static Decision blocked(String recommendation) { return new Decision(true, recommendation); }
    }
}
