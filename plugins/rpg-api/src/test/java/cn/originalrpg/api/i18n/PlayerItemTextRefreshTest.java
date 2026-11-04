package cn.originalrpg.api.i18n;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.event.PlayerLocaleChangeEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static cn.originalrpg.api.i18n.ItemTextPresentationTest.proxy;
import static org.junit.jupiter.api.Assertions.*;

class PlayerItemTextRefreshTest {
    @Test
    void requiresAuthenticationAndCoalescesChangesUntilTheNextMainThreadTask() {
        Fixture f = new Fixture();
        f.listener.onLocaleChanged(new PlayerLocaleChangeEvent(f.player, "zh_cn", "en_us"));
        assertEquals(0, f.tasks.size());
        f.authenticate();
        f.slotChanged();
        f.slotChanged();
        assertEquals(1, f.tasks.size());
        assertTrue(f.rendered.isEmpty());
        f.drain();
        assertEquals(List.of(f.player), f.rendered);
    }

    @Test
    void openForgeOrContainerIsNotReadAndClosingItResumesDeferredRefresh() {
        Fixture f = new Fixture();
        f.topType = "CHEST";
        f.authenticate();
        f.drain();
        assertTrue(f.rendered.isEmpty());
        f.listener.onClose(new InventoryCloseEvent(f.view));
        f.topType = "CRAFTING";
        f.drain();
        assertEquals(List.of(f.player), f.rendered);
        assertFalse(PlayerItemTextRefresh.personalView("ANVIL"));
        assertFalse(PlayerItemTextRefresh.personalView("SMITHING"));
    }

    @Test
    void asynchronousDeliveryBecomesReadableOnlyAfterReceiptRemovalSlotEvent() {
        Fixture f = new Fixture();
        NamespacedKey receipt = new NamespacedKey("rpgmarket", "delivery_id");
        f.item.state.keys.add(receipt);
        f.authenticate();
        f.drain();
        assertTrue(f.rendered.isEmpty());
        assertTrue(f.item.state.keys.contains(receipt));
        f.item.state.keys.remove(receipt);
        f.slotChanged();
        f.drain();
        assertEquals(List.of(f.player), f.rendered);
    }

    @Test
    void oldConnectionCallbackAndRevocationCannotConsumeNewConnectionsRefresh() {
        Fixture f = new Fixture();
        Player old = f.player;
        f.authenticate();
        f.player = f.newPlayer();
        f.authenticate();
        f.listener.onRevoked(new AuthenticatedPlayerRevokedEvent(old));
        f.drain();
        assertEquals(List.of(f.player), f.rendered);
    }

    @Test
    void closingListenerDiscardsQueuedWorkAndConnectionReferences() {
        Fixture f = new Fixture();
        f.authenticate();
        f.listener.close();
        f.drain();
        f.listener.onLocaleChanged(new PlayerLocaleChangeEvent(f.player, "zh_cn", "en_us"));
        assertTrue(f.tasks.isEmpty());
        assertTrue(f.rendered.isEmpty());
    }

    private static final class Stack extends ItemStack {
        final ItemTextPresentationTest.Meta state = new ItemTextPresentationTest.Meta();
        private Stack() { super(); }
        @Override public ItemMeta getItemMeta() { return state.meta; }
        @Override public Stack clone() { return this; }
    }

    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final List<Runnable> tasks = new ArrayList<>();
        final List<Player> rendered = new ArrayList<>();
        final Stack item = new Stack();
        String topType = "CRAFTING";
        final PlayerInventory personal = proxy(PlayerInventory.class, (object, method, args) -> switch (method.getName()) {
            case "getSize" -> 1;
            case "getItem" -> item;
            default -> throw new AssertionError("Unexpected personal inventory access: " + method.getName());
        });
        final Inventory ender = proxy(Inventory.class, (object, method, args) -> {
            if (method.getName().equals("getSize")) return 0;
            throw new AssertionError("Unexpected ender inventory access: " + method.getName());
        });
        final Inventory top = proxy(Inventory.class, (object, method, args) -> {
            if (method.getName().equals("getType")) return topType;
            throw new AssertionError("Open container inputs must never be accessed: " + method.getName());
        });
        Player player = newPlayer();
        final InventoryView view = proxy(InventoryView.class, (object, method, args) -> switch (method.getName()) {
            case "getTopInventory" -> top;
            case "getPlayer" -> player;
            case "convertSlot" -> args[0];
            default -> throw new AssertionError(method.getName());
        });
        final BukkitScheduler scheduler = proxy(BukkitScheduler.class, (object, method, args) -> {
            if (method.getName().equals("runTask") && args[1] instanceof Runnable task) {
                tasks.add(task); return null;
            }
            throw new AssertionError("Only main-thread tasks are allowed: " + method.getName());
        });
        final Server server = proxy(Server.class, (object, method, args) -> switch (method.getName()) {
            case "getScheduler" -> scheduler;
            case "getPlayer" -> player;
            default -> throw new AssertionError(method.getName());
        });
        final Plugin plugin = proxy(Plugin.class, (object, method, args) -> switch (method.getName()) {
            case "isEnabled" -> true;
            case "getServer" -> server;
            default -> throw new AssertionError(method.getName());
        });
        final PlayerItemTextRefresh listener = new PlayerItemTextRefresh(plugin, value -> value instanceof Stack,
                (audience, value) -> { rendered.add(audience); return false; },
                audience -> PlayerItemTextRefresh.personalView(topType));

        Player newPlayer() {
            return proxy(Player.class, (object, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "isOnline" -> true;
                case "getInventory" -> personal;
                case "getEnderChest" -> ender;
                case "getItemOnCursor" -> null;
                case "getOpenInventory" -> view;
                case "equals" -> object == args[0];
                case "hashCode" -> System.identityHashCode(object);
                case "toString" -> "player-" + System.identityHashCode(object);
                default -> throw new AssertionError(method.getName());
            });
        }
        void authenticate() { listener.onAuthenticated(new AuthenticatedPlayerReadyEvent(player)); }
        void slotChanged() { listener.onSlotChanged(new PlayerInventorySlotChangeEvent(player, 0, item, item)); }
        void drain() { List<Runnable> current = List.copyOf(tasks); tasks.clear(); current.forEach(Runnable::run); }
    }
}
