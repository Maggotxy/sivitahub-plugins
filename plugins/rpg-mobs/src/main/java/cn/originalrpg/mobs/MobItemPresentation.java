package cn.originalrpg.mobs;

import cn.originalrpg.api.i18n.ItemTextPresentation;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Text for the two adaptive materials and the common skill book, never loot rules. */
public final class MobItemPresentation {
    private static final NamespacedKey MATERIAL = new NamespacedKey("rpgmobs", "adaptive_material");
    private static final NamespacedKey BOOK = new NamespacedKey("rpgskills", "skill_book");
    private final MessageService messages;

    public MobItemPresentation(MessageService messages) {
        this.messages = messages;
    }

    public boolean managed(ItemStack item) {
        return item != null && item.hasItemMeta() && nameKey(item.getType(), item.getItemMeta()) != null;
    }

    public boolean refresh(CommandSender audience, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        String key = nameKey(item.getType(), meta);
        if (key == null || !ItemTextPresentation.mayRefresh(meta)) return false;
        NamedTextColor color = item.getType() == Material.ECHO_SHARD ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA;
        boolean changed = ItemTextPresentation.apply(meta, Component.text(messages.text(audience, key), color),
                messages.supportedLocales().stream().map(locale -> messages.textForLocale(locale, key)).toList(), null);
        if (changed) item.setItemMeta(meta);
        return changed;
    }

    static String nameKey(Material type, ItemMeta meta) {
        if (type == Material.ENCHANTED_BOOK
                && "common-insight".equals(meta.getPersistentDataContainer().get(BOOK, PersistentDataType.STRING))) {
            return "mobs.items.common-insight-book";
        }
        if (!Byte.valueOf((byte) 1).equals(meta.getPersistentDataContainer().get(MATERIAL, PersistentDataType.BYTE))) {
            return null;
        }
        return switch (type) {
            case ECHO_SHARD -> "mobs.items.mutation-core";
            case AMETHYST_SHARD -> "mobs.items.mutation-shard";
            default -> null;
        };
    }
}
