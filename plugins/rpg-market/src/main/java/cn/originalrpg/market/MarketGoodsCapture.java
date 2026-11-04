package cn.originalrpg.market;

import cn.originalrpg.api.model.MarketGoods;
import cn.originalrpg.api.service.EquipmentService;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Optional;

/** Called on the Paper thread before adding the escrow marker. */
final class MarketGoodsCapture {
    private MarketGoodsCapture() { }

    static Optional<MarketGoods> capture(ItemStack item, EquipmentService equipment) {
        if (item == null || item.getType().isAir() || item.getAmount() < 1 || item.getAmount() > 64) {
            return Optional.empty();
        }
        if (equipment.isCustomEquipment(item)) {
            if (!equipment.isAuthentic(item) || equipment.isBound(item) || item.getAmount() != 1) {
                return Optional.empty();
            }
            return equipment.inspect(item).flatMap(snapshot -> {
                String matcher = "equipment:" + snapshot.equipmentType().toLowerCase(Locale.ROOT)
                        + ":" + snapshot.rarity().toLowerCase(Locale.ROOT);
                return MarketGoods.validMatcher(matcher)
                        ? Optional.of(new MarketGoods(matcher, 1, snapshot.crafterId())) : Optional.empty();
            });
        }
        // Custom names, damage, enchantments, potion contents and unknown PDC
        // cannot silently satisfy a request for an ordinary vanilla material.
        if (item.hasItemMeta()) return Optional.empty();
        return Optional.of(new MarketGoods(item.getType().getKey().toString(), item.getAmount(), null));
    }
}
