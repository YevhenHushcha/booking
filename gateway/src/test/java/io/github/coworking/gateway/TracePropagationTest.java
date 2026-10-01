package io.github.coworking.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * If the gateway did not forward the W3C trace context, each service would start a new trace and
 * Jaeger would show four disconnected fragments per request instead of one tree.
 * <p>
 * Boot turns tracing off in tests by default; {@link AutoConfigureObservability} turns it back on.
 * Spans are not exported: there is no collector here, and the header is what is being tested.
 */
@AutoConfigureObservability
@TestPropertySource(properties = "management.otlp.tracing.export.enabled=false")
class TracePropagationTest extends GatewayIntegrationTest {

    private static final String TRACEPARENT = "traceparent";

    @Test
    void upstreamReceivesTheTraceContextSoItsSpansJoinTheGatewayTrace() {
        UPSTREAM.stubFor(get("/workspaces").willReturn(okJson("[]")));

        client.get().uri("/api/workspaces")
                .header(HttpHeaders.AUTHORIZATION, bearer(1))
                .exchange()
                .expectStatus().isOk();

        // version-traceId-parentSpanId-flags; flags 01 = sampled
        UPSTREAM.verify(getRequestedFor(urlEqualTo("/workspaces"))
                .withHeader(TRACEPARENT, matching("00-[0-9a-f]{32}-[0-9a-f]{16}-01")));
    }

    @Test
    void traceStartedByTheCallerIsContinuedNotReplaced() {
        UPSTREAM.stubFor(get("/workspaces").willReturn(okJson("[]")));
        String callerTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        client.get().uri("/api/workspaces")
                .header(HttpHeaders.AUTHORIZATION, bearer(1))
                .header(TRACEPARENT, "00-" + callerTraceId + "-00f067aa0ba902b7-01")
                .exchange()
                .expectStatus().isOk();

        UPSTREAM.verify(getRequestedFor(urlEqualTo("/workspaces"))
                .withHeader(TRACEPARENT, matching("00-" + callerTraceId + "-[0-9a-f]{16}-01")));
    }
}
