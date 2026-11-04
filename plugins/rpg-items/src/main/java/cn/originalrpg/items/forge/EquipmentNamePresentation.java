package cn.originalrpg.items.forge;

import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

/** Keeps the translated base name separate from a player's anvil rename. */
final class EquipmentNamePresentation {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private EquipmentNamePresentation() { }

    static void refresh(ItemMeta meta, MessageService messages, CommandSender audience,
                        String nameKey, NamedTextColor rarityColor, String crafterName) {
        // Before this migration generated names occupied CUSTOM_NAME. Recognize
        // exact shipped names in either language once; do not treat an arbitrary
        // player rename ending with "Forged Equipment" as a system-owned name.
        if (!meta.hasItemName() && meta.hasCustomName()) {
            String current = PLAIN.serialize(meta.customName());
            boolean generated = messages.supportedLocales().stream().anyMatch(locale ->
                    current.equals(messages.textForLocale(locale, nameKey))
                    || (crafterName != null && current.equals(messages.textForLocale(locale,
                            "items.forged-name", Map.of("player", crafterName)))));
            if (generated) meta.customName(null);
        }
        // ITEM_NAME is the localized base name. CUSTOM_NAME remains the player's
        // rename and takes precedence in Minecraft, including after locale changes.
        meta.itemName(Component.text(messages.text(audience, nameKey), rarityColor));
    }
}
