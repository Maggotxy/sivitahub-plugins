package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SignedSupportReceipt;
import cn.originalrpg.api.model.SupportPaymentResult;
import cn.originalrpg.api.model.SupportReceipt;
import cn.originalrpg.api.support.SupportReceiptCodec;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/** Uses the JDK's pure Ed25519 verifier; this code does not implement signature arithmetic or accept signing keys. */
final class SupportReceiptVerifier {
    private final SupportCreditPolicy policy;
    SupportReceiptVerifier(SupportCreditPolicy policy) { this.policy = policy; }

    Verified authenticate(SignedSupportReceipt signed) throws Rejected {
        if (signed == null) throw new Rejected(SupportPaymentResult.Status.INVALID);
        SupportReceipt receipt;
        try { receipt = SupportReceiptCodec.decode(signed.payload()); }
        catch (RuntimeException error) { throw new Rejected(SupportPaymentResult.Status.INVALID); }
        if (!policy.audience().equals(receipt.audience())) throw new Rejected(SupportPaymentResult.Status.WRONG_AUDIENCE);
        PublicKey key = policy.trustedKeys().get(receipt.keyId());
        if (key == null) throw new Rejected(SupportPaymentResult.Status.UNKNOWN_KEY);
        byte[] bytes = SupportReceiptCodec.bytes(receipt);
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(bytes);
            if (!verifier.verify(Base64.getUrlDecoder().decode(signed.signature()))) {
                throw new Rejected(SupportPaymentResult.Status.INVALID_SIGNATURE);
            }
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new Rejected(SupportPaymentResult.Status.INVALID_SIGNATURE);
        }
        return new Verified(receipt, digest(bytes), signed.payload(), signed.signature());
    }

    /** Only new orders are time-gated. A previously accepted exact order remains safely replayable after expiry. */
    SupportPaymentResult.Status newOrderTimeStatus(SupportReceipt receipt, Instant now) {
        if (Duration.between(receipt.issuedAt(), receipt.expiresAt()).compareTo(policy.maximumReceiptLifetime()) > 0) {
            return SupportPaymentResult.Status.INVALID;
        }
        if (receipt.issuedAt().isAfter(now.plus(policy.allowedClockSkew()))) return SupportPaymentResult.Status.NOT_YET_VALID;
        if (!receipt.expiresAt().isAfter(now)) return SupportPaymentResult.Status.EXPIRED;
        return null;
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    record Verified(SupportReceipt receipt, String payloadHash, String payload, String signature) { }
    static final class Rejected extends Exception {
        private final SupportPaymentResult.Status status;
        Rejected(SupportPaymentResult.Status status) { super(status.name()); this.status = status; }
        SupportPaymentResult.Status status() { return status; }
    }
}
