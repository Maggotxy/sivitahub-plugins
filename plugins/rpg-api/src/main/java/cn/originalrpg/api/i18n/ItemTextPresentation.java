package cn.originalrpg.api.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Presentation only; callers must first identify an item that their module owns. */
public final class ItemTextPresentation {
    private static final Set<String> PROTECTED_MARKERS = Set.of(
            "rpgmarket:listing_id", "rpgmarket:delivery_id", "rpgcore:equipment_operation",
            "rpgdungeons:ticket_entry_escrow",
            "rpgitems:signature");

    private ItemTextPresentation() {
    }

    /** Neither an escrow snapshot nor signed equipment belongs to this renderer. */
    public static boolean mayRefresh(ItemMeta meta) {
        return meta != null && meta.getPersistentDataContainer().getKeys().stream()
                .noneMatch(key -> PROTECTED_MARKERS.contains(key.toString()));
    }

    /**
     * Migrates a known old default name once, then keeps player customName intact.
     * A null lore argument leaves existing lore untouched. No PDC is written.
     * The result tells callers whether setting metadata would cause a real change.
     */
    public static boolean apply(
            ItemMeta meta, Component itemName, Collection<String> legacyDefaultNames, List<Component> lore
    ) {
        boolean changed = false;
        if (!meta.hasItemName() && meta.hasCustomName()) {
            String current = PlainTextComponentSerializer.plainText().serialize(meta.customName());
            if (legacyDefaultNames.contains(current)) {
                meta.customName(null);
                changed = true;
            }
        }
        if (!Objects.equals(meta.itemName(), itemName)) {
            meta.itemName(itemName);
            changed = true;
        }
        if (lore != null && !Objects.equals(meta.lore(), lore)) {
            meta.lore(lore);
            changed = true;
        }
        return changed;
    }
}
