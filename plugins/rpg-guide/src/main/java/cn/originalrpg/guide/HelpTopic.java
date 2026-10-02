package cn.originalrpg.guide;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Trusted navigation attached to each help chapter.
 *
 * <p>Commands are deliberately code-owned instead of read from language or
 * configuration files. Help text may be translated freely without turning a
 * resource file into an arbitrary command dispatcher.</p>
 */
enum HelpTopic {
    START("start", "open:current", "open:onboarding", "guide", "traveler_compass"),
    TASKS("tasks", "open:quests", "command:economy commissions", "quests", "legacy_blueprint"),
    PROFILE("profile", "command:rpg", "command:mobguide", "guide", "journey_attribute"),
    CLASSES("classes", "open:class", "command:skills", "class", "journey_class"),
    EQUIPMENT("equipment", "command:forge", "open:enhancement", "forge", "forged_chestplate"),
    MOBS("mobs", "command:mobguide", "command:worldstatus", "wilderness", "hunt_crystal"),
    DUNGEONS("dungeons", "open:dungeon", "command:dungeon party info", "dungeon", "dungeon_core"),
    ENHANCEMENT("enhancement", "open:enhancement", "command:economy shop", "enhancement", "mutation_core"),
    SKILLS("skills", "command:skills", "open:class", "skills", "common_insight_skill_book"),
    MARKET("market", "command:market list", "command:market claim", "market", null),
    ECONOMY("economy", "open:economy", "command:economy commissions", "economy", null),
    WORLD("world", "command:worldstatus", "command:mobguide", "wilderness", "blood_moon_essence"),
    STORE("store", "command:store", "open:economy", "economy", "lucky_charm"),
    SULFUR("sulfur", "command:imprint", "command:forge", "forge", "sulfur_resonance_imprint"),
    CLAIMS("claims", "command:claimslist", "command:claim", "wilderness", null),
    NPCS("npcs", "open:npcs", "command:ainpc", "guide", null),
    COMPANIONS("companions", "command:companion", "open:social", "guide", null),
    COMMUNITY("community", "command:community", "open:quests", "quests", null);

    private static final List<HelpTopic> ORDERED = List.copyOf(Arrays.asList(values()));

    private final String id;
    private final String primaryAction;
    private final String secondaryAction;
    private final String npcRole;
    private final String itemModel;

    HelpTopic(String id, String primaryAction, String secondaryAction, String npcRole, String itemModel) {
        this.id = id;
        this.primaryAction = primaryAction;
        this.secondaryAction = secondaryAction;
        this.npcRole = npcRole;
        this.itemModel = itemModel;
    }

    String id() {
        return id;
    }

    String primaryAction() {
        return primaryAction;
    }

    String secondaryAction() {
        return secondaryAction;
    }

    String npcRole() {
        return npcRole;
    }

    String itemModel() {
        return itemModel;
    }

    boolean usesCurrentJourneyNpc() {
        return this == START || this == NPCS;
    }

    String locatorAction(String currentJourneyRole) {
        String role = usesCurrentJourneyNpc() ? currentJourneyRole : npcRole;
        return role == null ? null : "command:hubfind " + role;
    }

    String visitAction(String currentJourneyRole) {
        String role = usesCurrentJourneyNpc() ? currentJourneyRole : npcRole;
        return role == null ? null : "command:hubfind " + role;
    }

    static List<HelpTopic> ordered() {
        return ORDERED;
    }

    static Optional<HelpTopic> find(String input) {
        if (input == null) return Optional.empty();
        String normalized = input.strip().toLowerCase(Locale.ROOT);
        return ORDERED.stream().filter(topic -> topic.id.equals(normalized)).findFirst();
    }
}
