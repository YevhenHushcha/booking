package io.github.coworking.gateway;

import io.github.coworking.jwt.AuthHeaders;
import io.github.coworking.jwt.TokenIssuer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

class RoutingAndAuthenticationTest extends GatewayIntegrationTest {

    @Test
    void routesToTheServiceWithoutTheApiPrefixAndPassesTheVerifiedIdentity() {
        UPSTREAM.stubFor(get("/workspaces").willReturn(okJson("[]")));

        client.get().uri("/api/workspaces")
                .header(HttpHeaders.AUTHORIZATION, bearer(42))
                .exchange()
                .expectStatus().isOk();

        UPSTREAM.verify(getRequestedFor(urlEqualTo("/workspaces"))
                .withHeader(AuthHeaders.USER_ID, equalTo("42"))
                .withHeader(AuthHeaders.USER_EMAIL, equalTo("user42@coworking.test"))
                .withHeader(AuthHeaders.USER_ROLES, equalTo("USER")));
    }

    @Test
    void eachPrefixGoesToItsOwnPath() {
        UPSTREAM.stubFor(get("/users/me").willReturn(okJson("{}")));
        UPSTREAM.stubFor(get("/bookings").willReturn(okJson("[]")));

        client.get().uri("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(1)).exchange().expectStatus().isOk();
        client.get().uri("/api/bookings").header(HttpHeaders.AUTHORIZATION, bearer(1)).exchange().expectStatus().isOk();

        UPSTREAM.verify(getRequestedFor(urlEqualTo("/users/me")));
        UPSTREAM.verify(getRequestedFor(urlEqualTo("/bookings")));
    }

    @Test
    void requestWithoutTokenIsRejectedBeforeReachingAnyService() {
        client.get().uri("/api/bookings")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

        UPSTREAM.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        TokenIssuer forger = new TokenIssuer(
                "a-different-secret-that-is-long-enough", Duration.ofMinutes(15), Clock.systemUTC());
        String forged = forger.issue(1, "admin@coworking.test", Collections.singletonList("ADMIN")).getValue();

        client.get().uri("/api/bookings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)
                .exchange()
                .expectStatus().isUnauthorized();

        UPSTREAM.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void expiredTokenIsRejected() {
        Clock anHourAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String expired = new TokenIssuer(JWT_SECRET, Duration.ofMinutes(15), anHourAgo)
                .issue(1, "user1@coworking.test", Collections.singletonList("USER")).getValue();

        client.get().uri("/api/bookings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void clientSuppliedIdentityHeadersAreReplacedByTheVerifiedOnes() {
        UPSTREAM.stubFor(get("/users/me").willReturn(okJson("{}")));

        client.get().uri("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, bearer(42))
                .header(AuthHeaders.USER_ID, "1")
                .header(AuthHeaders.USER_ROLES, "ADMIN")
                .exchange()
                .expectStatus().isOk();

        UPSTREAM.verify(getRequestedFor(urlEqualTo("/users/me"))
                .withHeader(AuthHeaders.USER_ID, equalTo("42"))
                .withHeader(AuthHeaders.USER_ROLES, equalTo("USER")));
    }

    @Test
    void loginIsPublicAndCannotCarryAnInjectedIdentity() {
        UPSTREAM.stubFor(post("/auth/login").willReturn(okJson("{}")));

        client.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header(AuthHeaders.USER_ID, "1")
                .bodyValue("{\"email\":\"a@b.c\",\"password\":\"x\"}")
                .exchange()
                .expectStatus().isOk();

        UPSTREAM.verify(postRequestedFor(urlEqualTo("/auth/login")).withHeader(AuthHeaders.USER_ID, absent()));
    }

    @Test
    void healthIsPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
