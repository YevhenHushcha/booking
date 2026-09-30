package io.github.coworking.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Test profile: the breaker opens at 50% failures over 4 calls and stays open for 1 s. */
class CircuitBreakerTest extends GatewayIntegrationTest {

    private static final String BREAKER = "workspace-service";

    @Test
    void opensAfterRepeatedServerErrorsAndThenFailsFastWithoutCallingTheService() {
        UPSTREAM.stubFor(get("/workspaces").willReturn(serverError()));

        for (int i = 0; i < 4; i++) {
            getWorkspaces().expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        assertThat(breakerState(BREAKER)).isEqualTo(CircuitBreaker.State.OPEN);

        getWorkspaces()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(503)
                .jsonPath("$.detail").value(detail -> assertThat((String) detail).contains("Circuit breaker"));

        // The fifth call never left the gateway.
        UPSTREAM.verify(4, getRequestedFor(urlEqualTo("/workspaces")));
    }

    @Test
    void clientErrorsDoNotOpenTheCircuit() {
        UPSTREAM.stubFor(get("/workspaces/99").willReturn(notFound()));

        for (int i = 0; i < 6; i++) {
            client.get().uri("/api/workspaces/99")
                    .header(HttpHeaders.AUTHORIZATION, bearer(1))
                    .exchange()
                    .expectStatus().isNotFound();
        }

        assertThat(breakerState(BREAKER)).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void closesAgainOnceTheServiceRecovers() {
        UPSTREAM.stubFor(get("/workspaces").willReturn(serverError()));
        for (int i = 0; i < 4; i++) {
            getWorkspaces();
        }
        assertThat(breakerState(BREAKER)).isEqualTo(CircuitBreaker.State.OPEN);

        UPSTREAM.stubFor(get("/workspaces").willReturn(okJson("[]")));

        // After the open-state wait, one trial call is let through; its success closes the circuit.
        await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                getWorkspaces().expectStatus().isOk());
        assertThat(breakerState(BREAKER)).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void slowServiceGetsGatewayTimeoutAndIsNotRetried() {
        // Test profile response-timeout is 500 ms.
        UPSTREAM.stubFor(get("/workspaces").willReturn(okJson("[]").withFixedDelay(1500)));

        getWorkspaces()
                .expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);

        UPSTREAM.verify(1, getRequestedFor(urlEqualTo("/workspaces")));
    }

    @Test
    void upstreamErrorBodyIsNotRelayedToTheClient() {
        UPSTREAM.stubFor(get("/workspaces").willReturn(aResponse().withStatus(500)
                .withBody("org.postgresql.util.PSQLException: connection to 10.0.0.5 refused")));

        getWorkspaces()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody(String.class)
                .value(body -> assertThat(body).doesNotContain("PSQLException").contains("responded with 500"));
    }

    private WebTestClient.ResponseSpec getWorkspaces() {
        return client.get().uri("/api/workspaces")
                .header(HttpHeaders.AUTHORIZATION, bearer(1))
                .exchange();
    }
}
