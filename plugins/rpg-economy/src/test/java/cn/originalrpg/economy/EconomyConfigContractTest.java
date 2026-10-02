package cn.originalrpg.economy;

import cn.originalrpg.api.model.EconomyActivity;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyConfigContractTest {
    @Test
    void everyActivityHasANonNegativePersistentDailyCap() {
        YamlConfiguration config = configuration();
        for (EconomyActivity activity : EconomyActivity.values()) {
            String path = "caps." + activity.name();
            assertTrue(config.isConfigurationSection(path), "missing cap for " + activity);
            assertTrue(new BigDecimal(config.getString(path + ".gold", "-1")).signum() >= 0,
                    "negative Gold cap for " + activity);
            assertTrue(config.getLong(path + ".merit", -1L) >= 0L,
                    "negative Merit cap for " + activity);
        }
    }

    @Test
    void renewableRaidsCannotConsumeTheOneTimeAdvancementPool() {
        YamlConfiguration config = configuration();
        BigDecimal raidReward = new BigDecimal(config.getString("rewards.vanilla.raid.gold", "-1"));
        long raidMerit = config.getLong("rewards.vanilla.raid.merit", -1L);

        assertEquals(new BigDecimal("1800.00"),
                new BigDecimal(config.getString("caps.RAID.gold")));
        assertEquals(24L, config.getLong("caps.RAID.merit"));
        assertTrue(new BigDecimal(config.getString("caps.RAID.gold")).compareTo(
                raidReward.multiply(BigDecimal.valueOf(3L))) == 0);
        assertEquals(raidMerit * 3L, config.getLong("caps.RAID.merit"));
        assertTrue(new BigDecimal(config.getString("caps.EXPLORATION.gold")).compareTo(
                new BigDecimal(config.getString("caps.RAID.gold"))) > 0);
    }

    @Test
    void documentedRepeatableActivityEnvelopeMatchesProductionConfiguration() {
        YamlConfiguration config = configuration();
        BigDecimal gold = BigDecimal.ZERO;
        long merit = 0L;
        for (EconomyActivity activity : new EconomyActivity[]{
                EconomyActivity.HUNTING, EconomyActivity.DUNGEON,
                EconomyActivity.GATHERING, EconomyActivity.RAID
        }) {
            gold = gold.add(new BigDecimal(config.getString("caps." + activity.name() + ".gold")));
            merit += config.getLong("caps." + activity.name() + ".merit");
        }

        assertEquals(new BigDecimal("11100.00"), gold,
                "update docs/ECONOMY.md whenever the repeatable Gold envelope changes");
        assertEquals(109L, merit,
                "update docs/ECONOMY.md whenever the repeatable Merit envelope changes");
    }

    @Test
    void enhancementAidPricesMatchEveryPlayerFacingMenu() {
        YamlConfiguration config = configuration();
        assertEquals(new BigDecimal("4500.00"),
                new BigDecimal(config.getString("earned-shop.protection-stone.gold")));
        assertEquals(30L, config.getLong("earned-shop.protection-stone.merit"));
        assertEquals(new BigDecimal("2200.00"),
                new BigDecimal(config.getString("earned-shop.lucky-charm.gold")));
        assertEquals(12L, config.getLong("earned-shop.lucky-charm.merit"));
    }

    private static YamlConfiguration configuration() {
        var stream = EconomyConfigContractTest.class.getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream, "packaged economy config.yml");
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
