package cn.originalrpg.story;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.PlayerLocaleChangeEvent;
import cn.originalrpg.api.service.MessageService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryLanguageSyncTest {
    @Test
    void authenticationUsesResolvedServerDefaultWithoutRequiringALanguageCommand() {
        List<String> commands = new ArrayList<>();
        Player player = player(true, commands);
        new StoryListener(messages(new AtomicReference<>("en_us")))
                .onAuthenticated(new AuthenticatedPlayerReadyEvent(player));
        assertEquals(List.of("questlang en-US"), commands);
    }

    @Test
    void actualPreferenceChangeSynchronizesBothDirectionsWithLocaleNormalization() {
        List<String> commands = new ArrayList<>();
        Player player = player(true, commands);
        AtomicReference<String> locale = new AtomicReference<>("en-US");
        StoryListener listener = new StoryListener(messages(locale));
        listener.onLanguageChange(new PlayerLocaleChangeEvent(player, "zh_cn", "en_us"));
        locale.set("zh_cn");
        listener.onLanguageChange(new PlayerLocaleChangeEvent(player, "en_us", "zh_cn"));
        assertEquals(List.of("questlang en-US", "questlang zh-CN"), commands);
    }

    @Test
    void disconnectedPlayersAndUnknownLocalesNeverReceiveGuessedCommands() {
        List<String> commands = new ArrayList<>();
        new StoryListener(messages(new AtomicReference<>("en_us")))
                .onAuthenticated(new AuthenticatedPlayerReadyEvent(player(false, commands)));
        new StoryListener(messages(new AtomicReference<>("fr_ca")))
                .onAuthenticated(new AuthenticatedPlayerReadyEvent(player(true, commands)));
        assertTrue(commands.isEmpty());
    }

    private static MessageService messages(AtomicReference<String> locale) {
        return (MessageService) Proxy.newProxyInstance(MessageService.class.getClassLoader(),
                new Class<?>[]{MessageService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("locale")) return locale.get();
                    throw new AssertionError("Unexpected message operation: " + method.getName());
                });
    }

    private static Player player(boolean online, List<String> commands) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isOnline" -> online;
                    case "performCommand" -> { commands.add((String) args[0]); yield true; }
                    default -> throw new AssertionError("Unexpected player operation: " + method.getName());
                });
    }
}
