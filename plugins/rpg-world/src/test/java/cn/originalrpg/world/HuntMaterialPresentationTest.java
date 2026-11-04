package cn.originalrpg.world;

import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class HuntMaterialPresentationTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void existingChineseLootMigratesAndRerendersInEitherLanguage() {
        Fixture f = new Fixture();
        f.customName = Component.text("狩猎结晶", NamedTextColor.AQUA);
        f.refresh("en_us", false, 17);
        assertNull(f.customName);
        assertEquals("Hunt Crystal", PLAIN.serialize(f.itemName));
        assertEquals(List.of("Hunt chain: 17"), f.lore.stream().map(PLAIN::serialize).toList());
        f.refresh("zh_cn", false, 17);
        assertEquals("狩猎结晶", PLAIN.serialize(f.itemName));
        assertEquals(List.of("狩猎连击：17"), f.lore.stream().map(PLAIN::serialize).toList());
    }

    @Test
    void bloodMoonLootKeepsItsDistinctNameColorAndChainValue() {
        Fixture f = new Fixture();
        f.refresh("en_us", true, 81);
        assertEquals("Blood Moon Essence", PLAIN.serialize(f.itemName));
        assertEquals(NamedTextColor.RED, f.itemName.color());
        assertEquals("Hunt chain: 81", PLAIN.serialize(f.lore.getFirst()));
    }

    @Test
    void playerRenameSurvivesRepeatedRefreshIncludingStockLookingRenameAfterMigration() {
        Fixture f = new Fixture();
        Component renamed = Component.text("Lamb's keepsake", NamedTextColor.GOLD);
        f.customName = renamed;
        f.refresh("en_us", false, 12);
        assertSame(renamed, f.customName);
        Component stockLookingRename = Component.text("狩猎结晶");
        f.customName = stockLookingRename;
        f.refresh("zh_cn", false, 12);
        f.refresh("en_us", false, 12);
        assertSame(stockLookingRename, f.customName);
        assertEquals("Hunt Crystal", PLAIN.serialize(f.itemName));
    }

    /** Strict boundary: data, model, ownership, enchantments and stack amount are never accessed. */
    private static final class Fixture {
        Component itemName;
        Component customName;
        List<Component> lore;
        String selected;
        final ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(),
                new Class<?>[]{ItemMeta.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasItemName" -> itemName != null;
                    case "hasCustomName" -> customName != null;
                    case "customName" -> {
                        if (args == null || args.length == 0) yield customName;
                        customName = (Component) args[0]; yield null;
                    }
                    case "itemName" -> { itemName = (Component) args[0]; yield null; }
                    case "lore" -> {
                        @SuppressWarnings("unchecked") List<Component> lines = (List<Component>) args[0];
                        lore = List.copyOf(lines); yield null;
                    }
                    default -> throw new AssertionError("Unexpected metadata access: " + method.getName());
                });
        final MessageService messages = (MessageService) Proxy.newProxyInstance(MessageService.class.getClassLoader(),
                new Class<?>[]{MessageService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "supportedLocales" -> Set.of("en_us", "zh_cn");
                    case "text" -> translate(selected, (String) args[1], args);
                    case "textForLocale" -> translate((String) args[0], (String) args[1], args);
                    default -> throw new AssertionError("Unexpected message access: " + method.getName());
                });
        void refresh(String locale, boolean bloodMoon, int chain) {
            selected = locale;
            HuntMaterialPresentation.refresh(meta, messages, null, bloodMoon, chain);
        }
        private static String translate(String locale, String key, Object[] args) {
            boolean english = locale.equals("en_us");
            return switch (key) {
                case "world.items.hunt-crystal" -> english ? "Hunt Crystal" : "狩猎结晶";
                case "world.items.blood-moon-essence" -> english ? "Blood Moon Essence" : "血月精华";
                case "world.items.chain-lore" -> (english ? "Hunt chain: " : "狩猎连击：")
                        + ((Map<?, ?>) args[2]).get("count");
                default -> throw new AssertionError("Unknown key: " + key);
            };
        }
    }
}
