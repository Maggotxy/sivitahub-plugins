package cn.originalrpg.api.ui;

import org.bukkit.inventory.InventoryHolder;

/**
 * Explicit opt-in marker for first-party menu inventories whose own click
 * routing is trustworthy, so the market's container-transfer policy skips
 * them. The previous rule trusted any holder whose class name started with
 * the plugin namespace, which silently granted the exemption to every future
 * inventory under that namespace — a bypass that widens itself. Implementing
 * this interface is a deliberate act, and a forgetting a new GUI opt in, it
 * fails closed: restricted items simply cannot be moved through its view.
 */
public interface TrustedMenuHolder extends InventoryHolder {
}
