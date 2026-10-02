package cn.originalrpg.market;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketConfigPolicyTest {
    @Test
    void productionFeesAndListingBoundsPass() {
        assertDoesNotThrow(() -> MarketConfigPolicy.validate(
                decimal("0.05"), decimal("0.10"), decimal("1.00"), decimal("10000000.00")));
    }

    @Test
    void packagedConfigurationKeepsFiveAndTenPercentFees() {
        var stream = MarketConfigPolicyTest.class.getClassLoader().getResourceAsStream("config.yml");
        assertNotNull(stream);
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Map<?, ?> config = (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(stream);
        Map<?, ?> fees = (Map<?, ?>) config.get("fees");
        assertEquals(decimal("0.05"), decimal(String.valueOf(fees.get("normal"))));
        assertEquals(decimal("0.10"), decimal(String.valueOf(fees.get("custom-equipment"))));
    }

    @Test
    void configurationCannotRemoveTheSinkOrMakeEquipmentCheaperToTrade() {
        assertThrows(IllegalArgumentException.class, () -> MarketConfigPolicy.validate(
                decimal("0.00"), decimal("0.10"), decimal("1.00"), decimal("10000000.00")));
        assertThrows(IllegalArgumentException.class, () -> MarketConfigPolicy.validate(
                decimal("0.05"), decimal("0.05"), decimal("1.00"), decimal("10000000.00")));
        assertThrows(IllegalArgumentException.class, () -> MarketConfigPolicy.validate(
                decimal("0.15"), decimal("0.10"), decimal("1.00"), decimal("10000000.00")));
    }

    @Test
    void configurationCannotOpenSubCentOrUnboundedListings() {
        assertThrows(IllegalArgumentException.class, () -> MarketConfigPolicy.validate(
                decimal("0.05"), decimal("0.10"), decimal("0.01"), decimal("10000000.00")));
        assertThrows(IllegalArgumentException.class, () -> MarketConfigPolicy.validate(
                decimal("0.05"), decimal("0.10"), decimal("1.00"), decimal("10000000.01")));
        assertThrows(IllegalArgumentException.class, () -> MarketConfigPolicy.validate(
                decimal("0.05"), decimal("0.10"), decimal("100.00"), decimal("99.99")));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
