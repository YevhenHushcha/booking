package io.github.coworking.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Signs access tokens with HS256. Thread-safe. */
public class TokenIssuer {

    private final MACSigner signer;
    private final Duration ttl;
    private final Clock clock;

    public TokenIssuer(String secret, Duration ttl, Clock clock) {
        try {
            this.signer = new MACSigner(JwtContract.signingKey(secret));
        } catch (JOSEException e) {
            throw new IllegalArgumentException("Unusable JWT secret", e);
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    public IssuedToken issue(long userId, String email, List<String> roles) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(JwtContract.ISSUER)
                .subject(Long.toString(userId))
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .claim(JwtContract.CLAIM_EMAIL, email)
                .claim(JwtContract.CLAIM_ROLES, roles)
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign token", e);
        }
        return new IssuedToken(jwt.serialize(), now, expiresAt);
    }

    public static final class IssuedToken {

        private final String value;
        private final Instant issuedAt;
        private final Instant expiresAt;

        IssuedToken(String value, Instant issuedAt, Instant expiresAt) {
            this.value = value;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
        }

        public String getValue() {
            return value;
        }

        public Instant getIssuedAt() {
            return issuedAt;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }
    }
}
