package io.github.coworking.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Limits scaled down so the bucket drains within a test and refills slowly enough not to interfere:
 * API 3 requests per user; login 2 attempts per IP.
 */
@TestPropertySource(properties = {
        "rate-limit.api.replenish-rate=1",
        "rate-limit.api.burst-capacity=3",
        "rate-limit.login.replenish-rate=1",
        "rate-limit.login.burst-capacity=120",
        "rate-limit.login.requested-tokens=60",
})
class RateLimitTest extends GatewayIntegrationTest {

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @Test
    void userIsLimitedAfterTheBurstAndToldWhenToRetry() {
        UPSTREAM.stubFor(get("/workspaces").willReturn(okJson("[]")));
        long user = uniqueUser();

        for (int i = 0; i < 3; i++) {
            getWorkspaces(user).expectStatus().isOk();
        }

        getWorkspaces(user)
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectHeader().valueEquals("X-RateLimit-Remaining", "0");

        UPSTREAM.verify(3, getRequestedFor(urlEqualTo("/workspaces")));
    }

    @Test
    void eachUserHasASeparateBucket() {
        UPSTREAM.stubFor(get("/workspaces").willReturn(okJson("[]")));
        long greedy = uniqueUser();
        long other = uniqueUser();

        for (int i = 0; i < 4; i++) {
            getWorkspaces(greedy);
        }
        getWorkspaces(greedy).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        getWorkspaces(other).expectStatus().isOk();
    }

    @Test
    void loginIsLimitedPerIpWithALongerWait() {
        UPSTREAM.stubFor(post("/auth/login").willReturn(okJson("{}")));
        // Anonymous requests are keyed by IP, and every test request comes from 127.0.0.1;
        // clear that bucket first so the order of tests does not matter.
        flushLoginBucket();

        login().expectStatus().isOk();
        login().expectStatus().isOk();
        login()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60");

        UPSTREAM.verify(2, postRequestedFor(urlEqualTo("/auth/login")));
    }

    private void flushLoginBucket() {
        redis.keys("request_rate_limiter.{ip:*").flatMap(redis::delete).blockLast();
    }

    private WebTestClient.ResponseSpec getWorkspaces(long user) {
        return client.get().uri("/api/workspaces").header(HttpHeaders.AUTHORIZATION, bearer(user)).exchange();
    }

    private WebTestClient.ResponseSpec login() {
        return client.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.c\",\"password\":\"x\"}")
                .exchange();
    }

    /** Buckets live in Redis and outlive a test, so every test uses users no other test has touched. */
    private static long uniqueUser() {
        return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
    }
}
