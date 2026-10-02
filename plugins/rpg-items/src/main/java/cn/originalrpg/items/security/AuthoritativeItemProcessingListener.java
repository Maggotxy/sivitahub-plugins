package cn.originalrpg.items.security;

import cn.originalrpg.api.rules.AuthoritativeItemPolicy;
import cn.originalrpg.api.rules.AuthoritativeItemPolicy.Operation;
import cn.originalrpg.api.rules.AuthoritativeItemPolicy.TransformEvidence;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.model.EquipmentOperationMarker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Crafter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.StonecutterInventory;

import java.util.Arrays;
import java.util.Objects;

/** Prevents metadata-blind vanilla stations from converting RPG assets. */
public final class AuthoritativeItemProcessingListener implements Listener {
    private final EquipmentService equipment;
    private final MessageService messages;

    public AuthoritativeItemProcessingListener(EquipmentService equipment, MessageService messages) {
        this.equipment = Objects.requireNonNull(equipment, "equipment");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        if (blocksMetadataBlind(Operation.CRAFTING, matrix)) event.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAutomaticCraft(CrafterCraftEvent event) {
        if (!(event.getBlock().getState() instanceof Crafter crafter)) {
            // The event contract says this is a crafter block. An invalid state
            // is not enough evidence to destroy an ordinary craft.
            return;
        }
        if (blocksMetadataBlind(Operation.AUTOMATIC_CRAFTING, crafter.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        if (blocksMetadataBlind(Operation.COOKING, event.getSource())) event.setCancelled(true);
    }

    /** Server items must not be consumed as furnace fuel either. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnaceFuel(FurnaceBurnEvent event) {
        if (AuthoritativeItemPolicy.isAuthoritative(event.getFuel())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        GrindstoneInventory inventory = event.getInventory();
        if (blocksMetadataBlind(Operation.GRINDSTONE,
                inventory.getUpperItem(), inventory.getLowerItem())) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        if (blockedPreservingTransform(Operation.ANVIL, inventory.getFirstItem(), event.getResult(),
                inventory.getSecondItem())) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        SmithingInventory inventory = event.getInventory();
        if (blockedPreservingTransform(Operation.SMITHING, inventory.getInputEquipment(), event.getResult(),
                inventory.getInputTemplate(), inventory.getInputMineral())) event.setResult(null);
    }

    /**
     * Stonecutters expose no prepare-result event. This also acts as the final
     * extraction gate if another plugin changes a result after a prepare event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResultClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (event.getSlotType() != InventoryType.SlotType.RESULT
                || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        if (!blocked(top, event.getCurrentItem())) return;
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            player.sendActionBar(Component.text(messages.text(
                    player, "items.authoritative-processing-blocked"), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMarkedOperationItemClick(InventoryClickEvent event) {
        if (EquipmentOperationMarker.isMarked(event.getCurrentItem())
                || EquipmentOperationMarker.isMarked(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    private boolean blocked(Inventory inventory, ItemStack result) {
        if (inventory instanceof StonecutterInventory stonecutter) {
            return blocksMetadataBlind(Operation.STONECUTTING, stonecutter.getInputItem());
        }
        if (inventory instanceof GrindstoneInventory grindstone) {
            return blocksMetadataBlind(Operation.GRINDSTONE,
                    grindstone.getUpperItem(), grindstone.getLowerItem());
        }
        if (inventory instanceof AnvilInventory anvil) {
            return blockedPreservingTransform(Operation.ANVIL,
                    anvil.getFirstItem(), result, anvil.getSecondItem());
        }
        if (inventory instanceof SmithingInventory smithing) {
            return blockedPreservingTransform(Operation.SMITHING, smithing.getInputEquipment(), result,
                    smithing.getInputTemplate(), smithing.getInputMineral());
        }
        if (inventory instanceof CraftingInventory crafting) {
            return blocksMetadataBlind(Operation.CRAFTING, crafting.getMatrix());
        }
        return false;
    }

    private boolean blocksMetadataBlind(Operation operation, ItemStack... inputs) {
        boolean authoritative = Arrays.stream(inputs)
                .anyMatch(AuthoritativeItemPolicy::isAuthoritative);
        return !AuthoritativeItemPolicy.allows(operation,
                new TransformEvidence(authoritative, false, false, false, false, false));
    }

    private boolean blockedPreservingTransform(
            Operation operation, ItemStack primary, ItemStack result, ItemStack... secondary
    ) {
        boolean primaryAuthoritative = AuthoritativeItemPolicy.isAuthoritative(primary);
        boolean authoritativeSecondary = Arrays.stream(secondary)
                .anyMatch(AuthoritativeItemPolicy::isAuthoritative);
        boolean authoritative = primaryAuthoritative || authoritativeSecondary;
        boolean sameIdentity = equipment.itemId(primary).isPresent()
                && equipment.itemId(primary).equals(equipment.itemId(result));
        TransformEvidence evidence = new TransformEvidence(
                authoritative,
                primaryAuthoritative && equipment.isAuthentic(primary),
                authoritativeSecondary,
                equipment.isAuthentic(result),
                sameIdentity,
                AuthoritativeItemPolicy.persistentDataPreserved(primary, result));
        return !AuthoritativeItemPolicy.allows(operation, evidence);
    }
}
