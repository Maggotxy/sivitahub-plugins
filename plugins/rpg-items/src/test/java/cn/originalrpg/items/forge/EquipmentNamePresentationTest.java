package cn.originalrpg.items.forge;

import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class EquipmentNamePresentationTest {
    private static final String KEY = "items.forged-names.warrior";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void migratesExistingGeneratedNamesAndSwitchesBothDirections() {
        Fixture f = new Fixture();
        f.customName = Component.text("铸锋战斧", NamedTextColor.AQUA);
        f.refresh("en_us");
        assertNull(f.customName);
        assertEquals("Forged War Axe", PLAIN.serialize(f.itemName));
        f.refresh("zh_cn");
        assertNull(f.customName);
        assertEquals("铸锋战斧", PLAIN.serialize(f.itemName));
        f.refresh("en_us");
        assertEquals(NamedTextColor.AQUA, f.itemName.color());
    }

    @Test
    void preservesPlayerRenameAndOnlyUpdatesTheBaseName() {
        Fixture f = new Fixture();
        Component custom = Component.text("My Dragonbreaker", NamedTextColor.RED);
        f.customName = custom;
        f.refresh("en_us");
        f.refresh("zh_cn");
        assertSame(custom, f.customName);
        assertEquals("铸锋战斧", PLAIN.serialize(f.itemName));
    }

    @Test
    void preservesAnvilRenameEqualToTheOtherLanguagesDefaultAfterMigration() {
        Fixture f = new Fixture();
        f.refresh("en_us");
        Component chosenByPlayer = Component.text("铸锋战斧");
        f.customName = chosenByPlayer;
        f.refresh("zh_cn");
        f.refresh("en_us");
        assertSame(chosenByPlayer, f.customName);
    }

    @Test
    void migratesTheKnownCraftersLegacyNameButNotArbitrarySuffixMatches() {
        Fixture f = new Fixture();
        f.customName = Component.text("Lamb's Forged Equipment");
        f.refresh("en_us");
        assertNull(f.customName);
        Fixture other = new Fixture();
        Component renamed = Component.text("Someone Else's Forged Equipment");
        other.customName = renamed;
        other.refresh("zh_cn");
        assertSame(renamed, other.customName);
    }

    /** A strict ItemMeta boundary: touching identity, lore or attributes fails. */
    private static final class Fixture {
        private Component itemName;
        private Component customName;
        private String selected = "en_us";
        private final ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(
                ItemMeta.class.getClassLoader(), new Class<?>[]{ItemMeta.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "hasItemName" -> itemName != null;
                        case "hasCustomName" -> customName != null;
                        case "customName" -> {
                            if (args == null || args.length == 0) yield customName;
                            customName = (Component) args[0];
                            yield null;
                        }
                        case "itemName" -> {
                            if (args == null || args.length == 0) yield itemName;
                            itemName = (Component) args[0];
                            yield null;
                        }
                        default -> throw new AssertionError("Unexpected metadata access: " + method.getName());
                    };
                });
        private final MessageService messages = (MessageService) Proxy.newProxyInstance(
                MessageService.class.getClassLoader(), new Class<?>[]{MessageService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "supportedLocales" -> Set.of("zh_cn", "en_us");
                    case "text" -> translate(selected, (String) args[1], args);
                    case "textForLocale" -> translate((String) args[0], (String) args[1], args);
                    default -> throw new AssertionError("Unexpected message access: " + method.getName());
                });

        void refresh(String locale) {
            selected = locale;
            EquipmentNamePresentation.refresh(meta, messages, null, KEY, NamedTextColor.AQUA, "Lamb");
        }

        private static String translate(String locale, String key, Object[] args) {
            if (key.equals(KEY)) return locale.equals("zh_cn") ? "铸锋战斧" : "Forged War Axe";
            if (key.equals("items.forged-name")) {
                Object crafter = ((Map<?, ?>) args[2]).get("player");
                return crafter + (locale.equals("zh_cn") ? "的自制装备" : "'s Forged Equipment");
            }
            throw new AssertionError("Unknown fixture key: " + key);
        }
    }
}
