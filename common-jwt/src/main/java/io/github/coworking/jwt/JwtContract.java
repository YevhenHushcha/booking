package io.github.coworking.jwt;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * What the issuer (user-service) and the verifier (gateway) must agree on about a token.
 * Keeping it in one class means a claim cannot be renamed on one side only.
 */
public final class JwtContract {

    /** Value of the {@code iss} claim; the gateway rejects tokens from any other issuer. */
    public static final String ISSUER = "coworking-user-service";

    public static final String CLAIM_EMAIL = "email";

    /** List of role names, e.g. {@code ["USER"]}. */
    public static final String CLAIM_ROLES = "roles";

    /** HS256 requires a key of at least 256 bits (RFC 7518, section 3.2). */
    private static final int MIN_SECRET_BYTES = 32;

    private JwtContract() {
    }

    /**
     * Builds the HS256 key from the shared secret, failing at startup rather than on the first
     * request when the secret is too short to be safe.
     */
    public static SecretKey signingKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret is not set");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least " + MIN_SECRET_BYTES + " bytes, got " + bytes.length);
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
