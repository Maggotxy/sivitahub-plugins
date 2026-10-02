package cn.originalrpg.guide;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.List;

/** Trusted hub NPC destinations shown by the player-facing service directory. */
enum GuideNpcTarget {
    GUIDE("guide", Material.COMPASS),
    QUESTS("quests", Material.WRITABLE_BOOK),
    AI("ai", Material.ECHO_SHARD),
    CLASS("class", Material.NETHER_STAR),
    SKILLS("skills", Material.ENCHANTED_BOOK),
    FORGE("forge", Material.ANVIL),
    ENHANCEMENT("enhancement", Material.SMITHING_TABLE),
    DUNGEON("dungeon", Material.TRIAL_KEY),
    WILDERNESS("wilderness", Material.RECOVERY_COMPASS),
    ECONOMY("economy", Material.GOLD_INGOT),
    MARKET("market", Material.EMERALD);

    private static final List<GuideNpcTarget> ORDERED = List.copyOf(Arrays.asList(values()));

    private final String id;
    private final Material icon;

    GuideNpcTarget(String id, Material icon) {
        this.id = id;
        this.icon = icon;
    }

    String id() {
        return id;
    }

    Material icon() {
        return icon;
    }

    String visitAction() {
        return "command:hubfind " + id;
    }

    String navigationCommand() {
        return "/hubfind " + id;
    }

    static List<GuideNpcTarget> ordered() {
        return ORDERED;
    }
}
