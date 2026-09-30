package io.github.coworking.jwt;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenIssuerTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-for-hs256";
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final TokenIssuer issuer =
            new TokenIssuer(SECRET, Duration.ofMinutes(15), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issuedTokenCarriesTheContractClaimsAndVerifiesWithTheSameSecret() throws Exception {
        TokenIssuer.IssuedToken token = issuer.issue(42L, "alice@coworking.test", List.of("USER"));

        SignedJWT jwt = SignedJWT.parse(token.value());
        assertThat(jwt.verify(new MACVerifier(JwtContract.signingKey(SECRET)))).isTrue();

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(JwtContract.ISSUER);
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.getStringClaim(JwtContract.CLAIM_EMAIL)).isEqualTo("alice@coworking.test");
        assertThat(claims.getStringListClaim(JwtContract.CLAIM_ROLES)).containsExactly("USER");
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(token.issuedAt()).isEqualTo(NOW);
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void tokenSignedWithAnotherSecretDoesNotVerify() throws Exception {
        TokenIssuer.IssuedToken token = issuer.issue(42L, "alice@coworking.test", List.of("USER"));

        MACVerifier otherKey = new MACVerifier(JwtContract.signingKey("another-secret-that-is-also-long-enough"));
        assertThat(SignedJWT.parse(token.value()).verify(otherKey)).isFalse();
    }

    @Test
    void secretShorterThan256BitsIsRejectedUpFront() {
        assertThatThrownBy(() -> JwtContract.signingKey("too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
    }
}
