package cn.originalrpg.guide;

import cn.originalrpg.api.i18n.ItemTextPresentation;
import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.ui.MenuLoreLayout;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Recognizes existing starter identities; never grants items or writes ownership. */
final class StarterItemPresentation {
    private static final NamespacedKey BASE = new NamespacedKey("rpgguide", "starter_base_owner");
    private static final NamespacedKey CORE = new NamespacedKey("rpgguide", "starter_core_owner");
    private static final NamespacedKey CLASS = new NamespacedKey("rpgguide", "starter_archetype");
    private static final NamespacedKey OWNER = new NamespacedKey("rpgitems", "material_owner");
    private final MessageService messages;

    StarterItemPresentation(MessageService messages) {
        this.messages = messages;
    }

    boolean managed(ItemStack item) {
        return item != null && item.hasItemMeta() && identify(item.getType(), item.getItemMeta()) != null;
    }

    boolean refresh(CommandSender audience, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        Kind kind = identify(item.getType(), meta);
        if (kind == null || !ItemTextPresentation.mayRefresh(meta)) return false;
        String locale = messages.locale(audience);
        boolean changed = ItemTextPresentation.apply(meta,
                Component.text(name(locale, kind), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                messages.supportedLocales().stream().map(code -> name(code, kind)).toList(),
                lore(messages.textForLocale(locale, "guide.starter-kit." + kind.loreKey()), kind.loreColor()));
        if (changed) item.setItemMeta(meta);
        return changed;
    }

    Kind identify(Material material, ItemMeta meta) {
        var data = meta.getPersistentDataContainer();
        // The earned-shop recovery core also has starter_core_owner. Its own
        // source/binding lore belongs to that producer, not the free starter kit.
        if (data.getKeys().stream().anyMatch(key -> key.getNamespace().equals("rpgcommerce"))) return null;
        if (data.has(BASE, PersistentDataType.STRING)) {
            Archetype archetype;
            try {
                archetype = Archetype.valueOf(data.getOrDefault(CLASS, PersistentDataType.STRING, "UNCHOSEN"));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            return archetype != Archetype.UNCHOSEN && material == StarterKitService.baseMaterial(archetype)
                    ? new Kind("base", "base-lore", NamedTextColor.GRAY, archetype) : null;
        }
        if (material == Material.ECHO_SHARD && data.has(CORE, PersistentDataType.STRING)) {
            return new Kind("core", "core-lore", NamedTextColor.AQUA, null);
        }
        String utility = switch (material) {
            case BREAD -> "food";
            case WHITE_BED -> "bed";
            case ARROW -> "arrows";
            default -> null;
        };
        if (utility != null && data.has(OWNER, PersistentDataType.STRING) && knownLore(meta, "utility-lore")) {
            return new Kind(utility, "utility-lore", NamedTextColor.DARK_GRAY, null);
        }
        // Older claim tools carry no PDC. Require both the exact shipped name
        // and lore; a renamed/unrelated golden shovel is deliberately untouched.
        if (material == Material.GOLDEN_SHOVEL && knownLore(meta, "claim-tool-lore")) {
            Component existing = meta.hasItemName() ? meta.itemName() : meta.customName();
            String current = existing == null ? "" : PlainTextComponentSerializer.plainText().serialize(existing);
            if (messages.supportedLocales().stream().anyMatch(locale -> current.equals(messages.textForLocale(locale,
                    "guide.starter-kit.claim-tool-name")))) {
                return new Kind("claim-tool", "claim-tool-lore", NamedTextColor.GRAY, null);
            }
        }
        return null;
    }

    private boolean knownLore(ItemMeta meta, String key) {
        List<Component> existing = meta.lore();
        if (existing == null) return false;
        List<String> text = existing.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
        return messages.supportedLocales().stream().anyMatch(locale -> text.equals(MenuLoreLayout.wrap(
                messages.textForLocale(locale, "guide.starter-kit." + key))));
    }

    private String name(String locale, Kind kind) {
        String key = "guide.starter-kit." + kind.id() + "-name";
        return kind.archetype() == null ? messages.textForLocale(locale, key) : messages.textForLocale(locale, key,
                Map.of("class", messages.textForLocale(locale,
                        "classes." + kind.archetype().name().toLowerCase(Locale.ROOT))));
    }

    private static List<Component> lore(String value, NamedTextColor color) {
        return MenuLoreLayout.wrap(value).stream().<Component>map(line -> Component.text(line, color)
                .decoration(TextDecoration.ITALIC, false)).toList();
    }

    record Kind(String id, String loreKey, NamedTextColor loreColor, Archetype archetype) {
    }
}
