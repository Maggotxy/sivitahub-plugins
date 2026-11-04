package cn.originalrpg.api.support;

import cn.originalrpg.api.model.SupportReceipt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Version-one interoperable signing format: the exact ASCII lines below, in
 * order, with LF separators and one final LF. No JSON canonicalization, locale
 * formatting, whitespace normalization or platform-specific newline is allowed.
 * The domain header and deployment audience separate these signatures from
 * other applications. Bridges sign {@link #bytes}, using pure Ed25519.
 */
public final class SupportReceiptCodec {
    private SupportReceiptCodec() { }

    public static byte[] bytes(SupportReceipt receipt) {
        String canonical = String.join("\n", "SIVITA_SUPPORT_RECEIPT",
                "schema=" + receipt.schema(), "keyId=" + receipt.keyId(), "audience=" + receipt.audience(),
                "orderId=" + receipt.orderId(), "playerUUID=" + receipt.playerId(), "creditUnits=" + receipt.creditUnits(),
                "issuedAt=" + receipt.issuedAt().getEpochSecond(), "expiresAt=" + receipt.expiresAt().getEpochSecond(),
                "currency=" + receipt.currency()) + "\n";
        return canonical.getBytes(StandardCharsets.US_ASCII);
    }

    public static String encode(SupportReceipt receipt) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(receipt));
    }

    /** Rejects alternate field order, duplicate fields, CRLF, padding, noncanonical numbers and unknown versions. */
    public static SupportReceipt decode(String encoded) {
        if (encoded == null || encoded.length() > 2048) throw new IllegalArgumentException("Receipt payload exceeds its bound");
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) {
            throw new IllegalArgumentException("Non-canonical receipt Base64url encoding");
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        if (lines.length != 11 || !lines[0].equals("SIVITA_SUPPORT_RECEIPT") || !lines[10].isEmpty()) {
            throw new IllegalArgumentException("Invalid receipt signing domain or field count");
        }
        SupportReceipt receipt = new SupportReceipt(Integer.parseInt(value(lines[1], "schema")),
                value(lines[2], "keyId"), value(lines[3], "audience"), value(lines[4], "orderId"),
                UUID.fromString(value(lines[5], "playerUUID")), Long.parseLong(value(lines[6], "creditUnits")),
                Instant.ofEpochSecond(Long.parseLong(value(lines[7], "issuedAt"))),
                Instant.ofEpochSecond(Long.parseLong(value(lines[8], "expiresAt"))), value(lines[9], "currency"));
        if (!MessageDigest.isEqual(bytes, bytes(receipt))) throw new IllegalArgumentException("Non-canonical receipt fields");
        return receipt;
    }

    private static String value(String line, String name) {
        String prefix = name + '=';
        if (!line.startsWith(prefix)) throw new IllegalArgumentException("Missing or reordered receipt field: " + name);
        return line.substring(prefix.length());
    }
}
