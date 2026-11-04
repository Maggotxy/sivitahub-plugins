package cn.originalrpg.mobs;

import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveMobNamePresentationTest {
    private final MessageService messages = (MessageService) Proxy.newProxyInstance(
            MessageService.class.getClassLoader(), new Class<?>[]{MessageService.class}, (proxy, method, args) -> {
                if (method.getName().equals("defaultLocale")) return "en_us";
                if (method.getName().equals("supportedLocales")) return Set.of("zh_cn", "en_us");
                if (!method.getName().equals("textForLocale")) throw new AssertionError(method.getName());
                boolean en = args[0].equals("en_us");
                String value = switch ((String) args[1]) {
                    case "world.rare-mob-name-unscaled" -> en ? "{elite}Blood Moon Rare · {name}" : "{elite}血月稀有 · {name}";
                    case "world.rare-mob-name" -> en ? "{elite}Blood Moon Rare · {name} · Lv.{level}" : "{elite}血月稀有 · {name} · Lv.{level}";
                    case "world.blood-moon-mobs.husk.name" -> en ? "Moon Husk" : "月骸";
                    case "mobs.elite-name" -> en ? "Elite [{affix}]" : "精英[{affix}]";
                    case "mobs.affixes.swift" -> en ? "Swift" : "迅捷";
                    case "mobs.behaviors.warden.name" -> en ? "Warden" : "守望者";
                    default -> throw new AssertionError(args[1]);
                };
                if (args.length == 3) for (var entry : ((Map<?, ?>) args[2]).entrySet()) {
                    value = value.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
                }
                return value;
            });

    @Test
    void lateAssignmentRecognizesOldRareNameAndKeepsRareIdentityWithNewLevel() {
        assertTrue(AdaptiveMobNamePresentation.mayReplace(messages, Component.text("血月稀有 · 月骸"), "husk"));
        String name = plain(AdaptiveMobNamePresentation.render(messages, 12, "swift", null, "entity.minecraft.husk", "husk"));
        assertEquals("Elite [Swift] Blood Moon Rare · Moon Husk · Lv.12", name);
    }

    @Test
    void nameTaggedRareOrOrdinaryMobIsNeverOverwrittenByDelayedAssignment() {
        Component renamed = Component.text("Lamb's Moon Guardian");
        assertFalse(AdaptiveMobNamePresentation.mayReplace(messages, renamed, "husk"));
        assertFalse(AdaptiveMobNamePresentation.mayReplace(messages, renamed, null));
        assertTrue(AdaptiveMobNamePresentation.mayReplace(messages, null, null));
    }

    @Test
    void sharedNormalAffixAndBehaviorLabelsUseTheServerLocale() {
        String elite = plain(AdaptiveMobNamePresentation.render(messages, 9, "swift", null, "entity.minecraft.husk", null));
        assertTrue(elite.startsWith("Elite [Swift] Lv.9 "));
        String behavior = plain(AdaptiveMobNamePresentation.render(messages, 9, null, "warden", "entity.minecraft.husk", null));
        assertTrue(behavior.startsWith("Lv.9 [Warden] "));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
