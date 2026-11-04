package cn.originalrpg.api.model;

import java.util.Base64;
import java.util.regex.Pattern;

/** Canonical unpadded Base64url payload and a 64-byte Ed25519 signature; never contains a private key. */
public record SignedSupportReceipt(String payload, String signature) {
    private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
    public SignedSupportReceipt {
        if (payload == null || payload.length() > 2048 || !BASE64URL.matcher(payload).matches()
                || signature == null || signature.length() != 86 || !BASE64URL.matcher(signature).matches()) {
            throw new IllegalArgumentException("Invalid signed receipt envelope");
        }
        byte[] signatureBytes = Base64.getUrlDecoder().decode(signature);
        if (signatureBytes.length != 64 || !Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes).equals(signature)) {
            throw new IllegalArgumentException("Non-canonical Ed25519 signature encoding");
        }
    }
}
