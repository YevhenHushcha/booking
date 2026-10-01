package io.github.coworking.gateway;

import brave.sampler.Sampler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * If the gateway did not forward the trace context, each service would start a new trace and
 * Jaeger would show four disconnected fragments per request instead of one tree.
 * <p>
 * Sleuth propagates in Zipkin's B3 format, as separate {@code X-B3-*} headers, and creates 64-bit
 * trace ids unless {@code spring.sleuth.trace-id128} is set. Spans are not exported here (the test
 * profile turns Zipkin off); the headers are what is being tested.
 */
class TracePropagationTest extends GatewayIntegrationTest {

    /**
     * {@code spring.sleuth.sampler.probability} is applied by Sleuth's Zipkin auto-configuration;
     * with Zipkin off, Sleuth falls back to {@code NEVER_SAMPLE} and forwards {@code X-B3-Sampled: 0}.
     */
    @TestConfiguration
    static class SampleEverything {

        @Bean
        Sampler sampler() {
            return Sampler.ALWAYS_SAMPLE;
        }
    }

    @Test
    void upstreamReceivesTheTraceContextSoItsSpansJoinTheGatewayTrace() {
        UPSTREAM.stubFor(get("/workspaces").willReturn(okJson("[]")));

        client.get().uri("/api/workspaces")
                .header(HttpHeaders.AUTHORIZATION, bearer(1))
                .exchange()
                .expectStatus().isOk();

        UPSTREAM.verify(getRequestedFor(urlEqualTo("/workspaces"))
                .withHeader("X-B3-TraceId", matching("[0-9a-f]{16}"))
                .withHeader("X-B3-SpanId", matching("[0-9a-f]{16}"))
                .withHeader("X-B3-Sampled", equalTo("1")));
    }

    @Test
    void traceStartedByTheCallerIsContinuedNotReplaced() {
        UPSTREAM.stubFor(get("/workspaces").willReturn(okJson("[]")));
        String callerTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        client.get().uri("/api/workspaces")
                .header(HttpHeaders.AUTHORIZATION, bearer(1))
                .header("b3", callerTraceId + "-00f067aa0ba902b7-1")
                .exchange()
                .expectStatus().isOk();

        UPSTREAM.verify(getRequestedFor(urlEqualTo("/workspaces"))
                .withHeader("X-B3-TraceId", equalTo(callerTraceId)));
    }
}
