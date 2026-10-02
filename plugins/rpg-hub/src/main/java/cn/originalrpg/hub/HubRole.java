package cn.originalrpg.hub;

import cn.originalrpg.api.service.GuideMenuService;
import org.bukkit.Color;
import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

enum HubRole {
    GUIDE("guide", 0, 12, 0.0F, Color.fromRGB(116, 158, 68), Material.COMPASS,
            GuideMenuService.Section.MAIN, null),
    QUESTS("quests", -6, 14, -20.0F, Color.fromRGB(91, 151, 92), Material.WRITABLE_BOOK,
            GuideMenuService.Section.QUESTS, null),
    AI("ai", 6, 14, 20.0F, Color.fromRGB(73, 151, 177), Material.ECHO_SHARD,
            null, "ainpc"),
    CLASS("class", -24, -24, -45.0F, Color.fromRGB(136, 93, 188), Material.NETHER_STAR,
            GuideMenuService.Section.CLASS, null),
    SKILLS("skills", 0, -32, 0.0F, Color.fromRGB(91, 116, 190), Material.ENCHANTED_BOOK,
            null, "skills"),
    FORGE("forge", 24, -24, 45.0F, Color.fromRGB(194, 113, 65), Material.NETHERITE_AXE,
            null, "forge"),
    ENHANCEMENT("enhancement", 32, 0, 90.0F, Color.fromRGB(62, 144, 151), Material.SMITHING_TABLE,
            GuideMenuService.Section.ENHANCEMENT, null),
    DUNGEON("dungeon", 24, 24, 135.0F, Color.fromRGB(151, 65, 74), Material.TRIAL_KEY,
            GuideMenuService.Section.DUNGEON, null),
    WILDERNESS("wilderness", 0, 40, 180.0F, Color.fromRGB(72, 128, 70), Material.RECOVERY_COMPASS,
            null, "wild"),
    ECONOMY("economy", -24, 24, -135.0F, Color.fromRGB(196, 157, 55), Material.GOLD_INGOT,
            GuideMenuService.Section.ECONOMY, null),
    MARKET("market", -32, 0, -90.0F, Color.fromRGB(57, 151, 102), Material.EMERALD,
            GuideMenuService.Section.MARKET, null);

    private final String id;
    private final int offsetX;
    private final int offsetZ;
    private final float yaw;
    private final Color color;
    private final Material heldItem;
    private final GuideMenuService.Section section;
    private final String command;

    HubRole(
            String id, int offsetX, int offsetZ, float yaw, Color color, Material heldItem,
            GuideMenuService.Section section, String command
    ) {
        this.id = id;
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
        this.yaw = yaw;
        this.color = color;
        this.heldItem = heldItem;
        this.section = section;
        this.command = command;
    }

    String id() {
        return id;
    }

    int offsetX() {
        return offsetX;
    }

    int offsetZ() {
        return offsetZ;
    }

    float yaw() {
        return yaw;
    }

    Color color() {
        return color;
    }

    Material heldItem() {
        return heldItem;
    }

    GuideMenuService.Section section() {
        return section;
    }

    String command() {
        return command;
    }

    static Optional<HubRole> parse(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
        normalized = switch (normalized) {
            case "quest", "task", "tasks" -> "quests";
            case "classes", "attribute", "attributes" -> "class";
            case "skill" -> "skills";
            case "smith", "craft" -> "forge";
            case "enhance", "refine" -> "enhancement";
            case "dungeons", "instance" -> "dungeon";
            case "shop", "auction" -> "market";
            case "wealth" -> "economy";
            case "wild", "frontier" -> "wilderness";
            case "listener" -> "ai";
            default -> normalized;
        };
        for (HubRole role : values()) {
            if (role.id.equals(normalized)) return Optional.of(role);
        }
        return Optional.empty();
    }
}
