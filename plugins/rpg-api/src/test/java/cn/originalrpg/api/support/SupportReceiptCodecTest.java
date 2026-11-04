package cn.originalrpg.api.support;

import cn.originalrpg.api.model.SignedSupportReceipt;
import cn.originalrpg.api.model.SupportReceipt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SupportReceiptCodecTest {
    private static final String CANONICAL = """
            SIVITA_SUPPORT_RECEIPT
            schema=1
            keyId=bridge-2026
            audience=sivita-production
            orderId=provider:order-17
            playerUUID=12345678-1234-5678-9abc-123456789abc
            creditUnits=500
            issuedAt=1804075200
            expiresAt=1804075800
            currency=SUPPORT_CREDIT
            """;

    @Test void independentAsciiFixtureMatchesTheInteroperableSigningBytes() {
        var receipt = new SupportReceipt(1, "bridge-2026", "sivita-production", "provider:order-17",
                UUID.fromString("12345678-1234-5678-9abc-123456789abc"), 500,
                Instant.ofEpochSecond(1804075200), Instant.ofEpochSecond(1804075800), SupportReceipt.CURRENCY);
        assertArrayEquals(CANONICAL.getBytes(StandardCharsets.US_ASCII), SupportReceiptCodec.bytes(receipt));
        assertEquals(receipt, SupportReceiptCodec.decode(encoded(CANONICAL)));
    }

    @Test void alternativeWhitespaceOrderingFieldsAndNumericRepresentationsAreRejected() {
        for (String bad : new String[]{CANONICAL.replace("\n", "\r\n"), CANONICAL.stripTrailing(),
                CANONICAL.replace("creditUnits=500", "creditUnits=0500"), CANONICAL.replace("schema=1", "schema=+1"),
                CANONICAL.replace("keyId=bridge-2026\naudience=sivita-production", "audience=sivita-production\nkeyId=bridge-2026"),
                CANONICAL.replace("currency=SUPPORT_CREDIT", "currency=GOLD"),
                CANONICAL.replace("keyId=bridge-2026", "keyId=bridge-2026\nkeyId=another"),
                CANONICAL.replace("123456789abc", "123456789ABC"), CANONICAL + "\n",
                CANONICAL.replace("orderId=provider:order-17", "orderId=provider:order-17 ")}) {
            assertThrows(IllegalArgumentException.class, () -> SupportReceiptCodec.decode(encoded(bad)));
        }
    }

    @Test void malformedEncodingAndOversizedEnvelopeAreRejectedBeforeVerification() {
        assertThrows(IllegalArgumentException.class, () -> SupportReceiptCodec.decode(encoded(CANONICAL) + "="));
        assertThrows(IllegalArgumentException.class, () -> SupportReceiptCodec.decode("x".repeat(2049)));
        assertThrows(IllegalArgumentException.class, () -> SupportReceiptCodec.decode("!invalid!"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
        assertThrows(IllegalArgumentException.class, () -> new SignedSupportReceipt("x".repeat(2049), signature));
        assertThrows(IllegalArgumentException.class, () -> new SignedSupportReceipt(encoded(CANONICAL), signature + "="));
        assertThrows(IllegalArgumentException.class, () -> new SignedSupportReceipt(encoded(CANONICAL), signature.substring(1)));
    }

    @Test void invalidUnitsAndSubsecondTimestampsCannotBeSignedThroughTheTypedApi() {
        assertThrows(IllegalArgumentException.class, () -> receipt(0, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> receipt(1_000_000_001L, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> receipt(1, Instant.EPOCH.plusNanos(1)));
    }

    /** RFC 8032 section 7.1 test 1, independently published public key, signature and empty message. */
    @Test void pinnedJdkVerifierAcceptsThePublishedRfc8032VectorAndRejectsAChangedMessage() throws Exception {
        byte[] publicKey = HexFormat.of().parseHex("302a300506032b6570032100"
                + "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] signature = HexFormat.of().parseHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555f"
                + "b8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key); verifier.update(new byte[0]); assertTrue(verifier.verify(signature));
        verifier.initVerify(key); verifier.update(new byte[]{1}); assertFalse(verifier.verify(signature));
    }

    private static SupportReceipt receipt(long units, Instant issued) {
        return new SupportReceipt(1, "k", "a", "o", UUID.randomUUID(), units, issued, issued.plusSeconds(60), SupportReceipt.CURRENCY);
    }
    private static String encoded(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
}
