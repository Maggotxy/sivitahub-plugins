package cn.originalrpg.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

final class MarketPricePolicy {
    private MarketPricePolicy() {
    }

    static Optional<BigDecimal> normalize(String input, BigDecimal minimum, BigDecimal maximum) {
        if (input == null || !input.matches("[0-9]+(?:\\.[0-9]{1,2})?")) return Optional.empty();
        try {
            BigDecimal price = new BigDecimal(input).setScale(2, RoundingMode.UNNECESSARY);
            return price.compareTo(minimum) < 0 || price.compareTo(maximum) > 0
                    ? Optional.empty() : Optional.of(price);
        } catch (ArithmeticException | NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
