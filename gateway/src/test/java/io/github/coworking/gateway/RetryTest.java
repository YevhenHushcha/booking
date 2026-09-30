package io.github.coworking.gateway;

import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;

class RetryTest extends GatewayIntegrationTest {

    @Test
    void getIsRetriedAfterServiceUnavailableAndSucceeds() {
        UPSTREAM.stubFor(get("/bookings").inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(serviceUnavailable()).willSetStateTo("recovered"));
        UPSTREAM.stubFor(get("/bookings").inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson("[]")));

        getBookings().expectStatus().isOk();

        UPSTREAM.verify(2, getRequestedFor(urlEqualTo("/bookings")));
    }

    @Test
    void getGivesUpAfterTwoRetries() {
        UPSTREAM.stubFor(get("/bookings").willReturn(serviceUnavailable()));

        getBookings().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        UPSTREAM.verify(3, getRequestedFor(urlEqualTo("/bookings")));
    }

    @Test
    void getIsRetriedWhenTheConnectionIsResetAndEndsInBadGateway() {
        UPSTREAM.stubFor(get("/bookings").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        getBookings()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);

        UPSTREAM.verify(3, getRequestedFor(urlEqualTo("/bookings")));
    }

    @Test
    void internalServerErrorIsNotRetried() {
        UPSTREAM.stubFor(get("/bookings").willReturn(serverError()));

        getBookings().expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        UPSTREAM.verify(1, getRequestedFor(urlEqualTo("/bookings")));
    }

    @Test
    void postIsNeverRetriedBecauseItWouldCreateADuplicateBooking() {
        UPSTREAM.stubFor(post("/bookings").willReturn(serviceUnavailable()));

        client.post().uri("/api/bookings")
                .header(HttpHeaders.AUTHORIZATION, bearer(1))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"workspaceId\":1,\"startsAt\":\"2026-10-01T09:00:00Z\",\"endsAt\":\"2026-10-01T13:00:00Z\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        UPSTREAM.verify(1, postRequestedFor(urlEqualTo("/bookings")));
    }

    private WebTestClient.ResponseSpec getBookings() {
        return client.get().uri("/api/bookings")
                .header(HttpHeaders.AUTHORIZATION, bearer(1))
                .exchange();
    }
}
