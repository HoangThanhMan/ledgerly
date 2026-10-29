package dev.ledgerly.identity.internal.application;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** The key that signs and verifies the API's JWTs (HS256). */
public final class JwtKeys {

    /** HS256 needs a key at least as long as its 256-bit hash. */
    private static final int MIN_BYTES = 32;

    private JwtKeys() {}

    /**
     * Decodes the configured secret into an HS256 key.
     *
     * @param base64 the value of {@code ledgerly.security.jwt.secret}
     * @throws IllegalArgumentException if the key is shorter than 32 bytes
     */
    public static SecretKey secretKey(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        if (bytes.length < MIN_BYTES) {
            throw new IllegalArgumentException("ledgerly.security.jwt.secret must decode to at least " + MIN_BYTES
                    + " bytes, it has " + bytes.length);
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
