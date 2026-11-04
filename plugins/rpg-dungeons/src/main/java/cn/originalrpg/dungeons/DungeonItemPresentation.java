package cn.originalrpg.dungeons;

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

import java.util.List;
import java.util.Map;

/** Resolves text from the same validated reward definitions used by the producer. */
final class DungeonItemPresentation {
    private static final NamespacedKey CORE = new NamespacedKey("rpgdungeons", "boss_material");
    private static final NamespacedKey TICKET = new NamespacedKey("rpgdungeons", "ticket_id");
    private static final NamespacedKey BOOK = new NamespacedKey("rpgskills", "skill_book");
    private final MessageService messages;
    private final List<TextSpec> specs;

    DungeonItemPresentation(MessageService messages, List<TextSpec> specs) {
        this.messages = messages;
        this.specs = List.copyOf(specs);
    }

    boolean managed(ItemStack item) {
        return item != null && item.hasItemMeta() && identify(item.getType(), item.getItemMeta()) != null;
    }

    boolean refresh(CommandSender audience, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        Match match = identify(item.getType(), meta);
        if (match == null || !ItemTextPresentation.mayRefresh(meta)) return false;
        TextSpec spec = match.spec();
        String nameKey;
        NamedTextColor color;
        List<Component> lore;
        String source = messages.text(audience, "dungeon.names." + spec.id());
        switch (match.kind()) {
            case CORE -> {
                boolean hidden = spec.id().endsWith("-hidden");
                nameKey = spec.materialNameKey();
                color = hidden ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA;
                lore = List.of(
                        Component.text(messages.text(audience, "dungeon.items.core-source", Map.of("dungeon", source)), NamedTextColor.GRAY),
                        Component.text(messages.text(audience, hidden ? "dungeon.items.core-tier-hidden" : "dungeon.items.core-tier-normal"), color),
                        Component.text(messages.text(audience, "dungeon.items.core-use"), NamedTextColor.DARK_GRAY));
            }
            case TICKET -> {
                nameKey = spec.ticketNameKey();
                color = NamedTextColor.LIGHT_PURPLE;
                lore = List.of(
                        Component.text(messages.text(audience, "dungeon.items.ticket-source", Map.of("dungeon", source)), NamedTextColor.GRAY),
                        Component.text(messages.text(audience, "dungeon.items.ticket-opens", Map.of("dungeon",
                                messages.text(audience, "dungeon.names." + spec.hiddenDungeonId()))), color),
                        Component.text(messages.text(audience, "dungeon.items.ticket-rule"), NamedTextColor.DARK_GRAY));
            }
            case BOOK -> {
                nameKey = spec.bookNameKey();
                color = NamedTextColor.GOLD;
                lore = List.of(
                        Component.text(messages.text(audience, "dungeon.items.book-source", Map.of("dungeon", source)), NamedTextColor.GRAY),
                        Component.text(messages.text(audience, "dungeon.items.book-use-" + spec.bookId()), NamedTextColor.AQUA));
            }
            default -> throw new IllegalStateException("Unhandled reward text kind");
        }
        boolean changed = ItemTextPresentation.apply(meta, Component.text(messages.text(audience, nameKey), color),
                messages.supportedLocales().stream().map(locale -> messages.textForLocale(locale, nameKey)).toList(), lore);
        if (changed) item.setItemMeta(meta);
        return changed;
    }

    Match identify(Material material, ItemMeta meta) {
        String core = meta.getPersistentDataContainer().get(CORE, PersistentDataType.STRING);
        String ticket = meta.getPersistentDataContainer().get(TICKET, PersistentDataType.STRING);
        String book = meta.getPersistentDataContainer().get(BOOK, PersistentDataType.STRING);
        if ((core == null ? 0 : 1) + (ticket == null ? 0 : 1) + (book == null ? 0 : 1) != 1) return null;
        List<Match> matches = specs.stream().map(spec -> {
            if (core != null && core.equals(spec.id()) && material == spec.material()) return new Match(Kind.CORE, spec);
            if (ticket != null && material == Material.PAPER && ticket.equals(spec.hiddenDungeonId())) return new Match(Kind.TICKET, spec);
            if (book != null && material == Material.ENCHANTED_BOOK && book.equals(spec.bookId())) return new Match(Kind.BOOK, spec);
            return null;
        }).filter(java.util.Objects::nonNull).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    record TextSpec(String id, Material material, String materialNameKey,
                    String hiddenDungeonId, String ticketNameKey, String bookId, String bookNameKey) {
    }

    enum Kind { CORE, TICKET, BOOK }
    record Match(Kind kind, TextSpec spec) { }
}
