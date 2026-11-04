package cn.originalrpg.pets;

import cn.originalrpg.api.i18n.ItemTextPresentation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** The existing bound signal horn; pet state, owner, glint and cooldown are untouched. */
final class PetSignalPresentation {
    private static final NamespacedKey SIGNAL = new NamespacedKey("rpgpets", "companion_signal");
    private final PetMessages messages;

    PetSignalPresentation(PetMessages messages) {
        this.messages = messages;
    }

    boolean managed(ItemStack item) {
        return item != null && item.getType() == Material.GOAT_HORN && item.hasItemMeta()
                && Byte.valueOf((byte) 1).equals(item.getItemMeta().getPersistentDataContainer()
                        .get(SIGNAL, PersistentDataType.BYTE));
    }

    boolean refresh(CommandSender audience, ItemStack item) {
        if (!managed(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (!ItemTextPresentation.mayRefresh(meta)) return false;
        boolean changed = ItemTextPresentation.apply(meta,
                Component.text(messages.render(audience, "signal-name"), NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false),
                messages.locales().stream().map(locale -> messages.renderForLocale(locale, "signal-name")).toList(),
                messages.renderLines(audience, "signal-lore").stream().<Component>map(line ->
                        Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
        if (changed) item.setItemMeta(meta);
        return changed;
    }
}
