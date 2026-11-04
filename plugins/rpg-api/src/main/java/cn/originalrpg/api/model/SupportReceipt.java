package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Provider-neutral statement issued by a trusted payment bridge after it verifies
 * a settled provider order. The Minecraft plugin verifies the bridge signature;
 * it does not accept card details or assert that a provider webhook was genuine.
 * All text fields are ASCII and timestamps have whole-second precision.
 */
public record SupportReceipt(int schema, String keyId, String audience, String orderId, UUID playerId,
                             long creditUnits, Instant issuedAt, Instant expiresAt, String currency) {
    public static final String CURRENCY = "SUPPORT_CREDIT";
    public static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,48}");
    public static final Pattern AUDIENCE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9._:-]{1,96}");
    public SupportReceipt {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (schema != 1 || keyId == null || !KEY_ID.matcher(keyId).matches()
                || audience == null || !AUDIENCE.matcher(audience).matches()
                || orderId == null || !ORDER_ID.matcher(orderId).matches()
                || creditUnits < 1 || creditUnits > 1_000_000_000L
                || issuedAt.isBefore(Instant.EPOCH) || issuedAt.getNano() != 0 || expiresAt.getNano() != 0
                || !expiresAt.isAfter(issuedAt) || !CURRENCY.equals(currency)) {
            throw new IllegalArgumentException("Invalid support receipt fields");
        }
    }
}
