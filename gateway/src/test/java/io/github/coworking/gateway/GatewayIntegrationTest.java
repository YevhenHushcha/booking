package io.github.coworking.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.coworking.jwt.TokenIssuer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Gateway in front of a WireMock server that plays all three services, with a real Redis for the
 * rate limiter.
 * <p>
 * WireMock and Redis are started once per JVM, not per class: Spring caches the application context
 * across test classes, and a cached context must not point at a port that has been shut down.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class GatewayIntegrationTest {

    protected static final String JWT_SECRET = "gateway-test-secret-long-enough-for-hs256";

    protected static final WireMockServer UPSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    @SuppressWarnings("resource") // stopped by Testcontainers' Ryuk container when the JVM exits
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        UPSTREAM.start();
        REDIS.start();
        Runtime.getRuntime().addShutdownHook(new Thread(UPSTREAM::stop));
    }

    private static final TokenIssuer TOKENS = new TokenIssuer(JWT_SECRET, Duration.ofMinutes(15), Clock.systemUTC());

    @Autowired
    protected WebTestClient client;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @DynamicPropertySource
    static void wireUpstreams(DynamicPropertyRegistry registry) {
        registry.add("services.user-service", UPSTREAM::baseUrl);
        registry.add("services.workspace-service", UPSTREAM::baseUrl);
        registry.add("services.booking-service", UPSTREAM::baseUrl);
        registry.add("spring.redis.host", REDIS::getHost);
        registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void resetUpstreamAndBreakers() {
        UPSTREAM.resetAll();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    protected static String bearer(long userId) {
        return "Bearer " + TOKENS.issue(userId, "user" + userId + "@coworking.test", Collections.singletonList("USER")).getValue();
    }

    protected CircuitBreaker.State breakerState(String name) {
        return circuitBreakers.circuitBreaker(name).getState();
    }
}
