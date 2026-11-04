package cn.originalrpg.api.i18n;

import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ItemTextPresentationTest {
    @Test
    void oldDefaultMigratesOnceAndRepeatedRenderingDoesNotWriteOrLoop() {
        Meta state = new Meta();
        state.customName = Component.text("异变核心");
        var aliases = List.of("异变核心", "Mutation Core");
        assertTrue(ItemTextPresentation.apply(state.meta, Component.text("Mutation Core"), aliases, null));
        assertNull(state.customName);
        int writes = state.writes;
        assertFalse(ItemTextPresentation.apply(state.meta, Component.text("Mutation Core"), aliases, null));
        assertEquals(writes, state.writes);
        assertTrue(ItemTextPresentation.apply(state.meta, Component.text("异变核心"), aliases, null));
        assertEquals(Component.text("异变核心"), state.itemName);
    }

    @Test
    void playerRenameAndUnownedLoreSurviveWhileOwnedLoreCanChangeLanguage() {
        Meta state = new Meta();
        Component rename = Component.text("Lamb's keepsake");
        state.customName = rename;
        state.lore = List.of(Component.text("A personal note"));
        ItemTextPresentation.apply(state.meta, Component.text("Bond Horn"), List.of("灵契号角"), null);
        assertSame(rename, state.customName);
        assertEquals(List.of(Component.text("A personal note")), state.lore);
        state.customName = Component.text("灵契号角"); // A stock-looking rename after migration is still a rename.
        ItemTextPresentation.apply(state.meta, Component.text("灵契号角"), List.of("灵契号角"),
                List.of(Component.text("右键使用")));
        assertEquals(Component.text("灵契号角"), state.customName);
        assertEquals(List.of(Component.text("右键使用")), state.lore);
    }

    @Test
    void receiptsEscrowAndSignedEquipmentRemainProtectedUntilTheirProducerClearsThem() {
        for (String marker : List.of("rpgmarket:listing_id", "rpgmarket:delivery_id", "rpgmarket:delivery_claim_token",
                "rpgcore:equipment_operation", "rpgdungeons:ticket_entry_escrow", "rpgitems:signature")) {
            Meta state = new Meta();
            NamespacedKey key = NamespacedKey.fromString(marker);
            state.keys.add(key);
            assertFalse(ItemTextPresentation.mayRefresh(state.meta), marker);
            assertEquals(Set.of(key), state.keys);
            assertEquals(0, state.writes);
            state.keys.clear(); // Only the authoritative producer removes its own marker.
            assertTrue(ItemTextPresentation.mayRefresh(state.meta));
        }
    }

    /** All metadata access except display fields and a read of PDC keys fails loudly. */
    static final class Meta {
        Component itemName;
        Component customName;
        List<Component> lore;
        int writes;
        final Set<NamespacedKey> keys = new HashSet<>();
        final PersistentDataContainer data = proxy(PersistentDataContainer.class, (object, method, args) -> {
            if (method.getName().equals("getKeys")) return Set.copyOf(keys);
            throw new AssertionError("Persistent identity must not be accessed or changed: " + method.getName());
        });
        final ItemMeta meta = proxy(ItemMeta.class, (object, method, args) -> switch (method.getName()) {
            case "getPersistentDataContainer" -> data;
            case "hasItemName" -> itemName != null;
            case "hasCustomName" -> customName != null;
            case "itemName" -> {
                if (args == null || args.length == 0) yield itemName;
                itemName = (Component) args[0]; writes++; yield null;
            }
            case "customName" -> {
                if (args == null || args.length == 0) yield customName;
                customName = (Component) args[0]; writes++; yield null;
            }
            case "lore" -> {
                if (args == null || args.length == 0) yield lore;
                @SuppressWarnings("unchecked") List<Component> values = (List<Component>) args[0];
                lore = List.copyOf(values); writes++; yield null;
            }
            default -> throw new AssertionError("Unexpected metadata mutation: " + method.getName());
        });
    }

    static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
