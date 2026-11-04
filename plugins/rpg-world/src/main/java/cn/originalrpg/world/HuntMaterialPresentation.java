package cn.originalrpg.world;

import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/** Presentation only: item identity, ownership, quantity and chain data are untouched. */
final class HuntMaterialPresentation {
    private HuntMaterialPresentation() { }

    static void refresh(ItemMeta meta, MessageService messages, CommandSender audience, boolean bloodMoon, int chain) {
        String key = bloodMoon ? "world.items.blood-moon-essence" : "world.items.hunt-crystal";
        if (!meta.hasItemName() && meta.hasCustomName()) {
            String current = PlainTextComponentSerializer.plainText().serialize(meta.customName());
            if (messages.supportedLocales().stream().anyMatch(locale -> current.equals(messages.textForLocale(locale, key)))) {
                meta.customName(null);
            }
        }
        meta.itemName(Component.text(messages.text(audience, key),
                bloodMoon ? NamedTextColor.RED : NamedTextColor.AQUA));
        meta.lore(List.of(Component.text(messages.text(audience, "world.items.chain-lore", Map.of("count", chain)),
                NamedTextColor.GRAY)));
    }
}
