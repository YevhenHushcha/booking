package io.github.coworking.e2e;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The real stack from docker-compose.yml, images built from the current sources, exercised only
 * through the gateway, the way a client sees it.
 * <p>
 * One stack for the whole class: starting it takes minutes. Tests are ordered because the last two
 * leave marks a later test would trip over: the login limit for the test client's IP is used up,
 * and a service is stopped.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CoworkingStackIT {

    private static final Duration STARTUP = Duration.ofMinutes(5);

    @Container
    static final ComposeContainer STACK = new ComposeContainer(
            new File("../docker-compose.yml"),
            new File("src/test/resources/compose-e2e.yml"))
            .withLocalCompose(true)
            .withBuild(true)
            .withRemoveImages(ComposeContainer.RemoveImages.LOCAL)
            // Test values, independent of anyone's .env; environment variables take precedence over it.
            .withEnv(Map.of(
                    "POSTGRES_PASSWORD", "e2e-postgres",
                    "DB_PASSWORD", "e2e-app",
                    "JWT_SECRET", "e2e-secret-that-is-long-enough-for-hs256",
                    "GRAFANA_ADMIN_PASSWORD", "unused"))
            // Grafana and Prometheus only display data; nothing here asserts on them.
            .withServices("gateway", "user-service", "workspace-service", "booking-service", "jaeger")
            .withExposedService("gateway", 8080, Wait.forHealthcheck().withStartupTimeout(STARTUP))
            .withExposedService("jaeger", 16686, Wait.forListeningPort().withStartupTimeout(STARTUP))
            .waitingFor("user-service", Wait.forHealthcheck().withStartupTimeout(STARTUP))
            .waitingFor("workspace-service", Wait.forHealthcheck().withStartupTimeout(STARTUP))
            .waitingFor("booking-service", Wait.forHealthcheck().withStartupTimeout(STARTUP));

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static String aliceToken;
    private static String bobToken;
    private static String bobId;

    @BeforeAll
    static void logIn() throws Exception {
        aliceToken = accessToken("alice@coworking.test");
        bobToken = accessToken("bob@coworking.test");
        bobId = JsonPath.read(send(get("/api/users/me", bobToken)).body(), "$.id").toString();
    }

    @Test
    @Order(1)
    void aliceBooksAWorkspaceAndOnlySheCanSeeTheBooking() throws Exception {
        HttpResponse<String> workspaces = send(get("/api/workspaces", aliceToken));
        assertThat(workspaces.statusCode()).isEqualTo(200);
        List<Integer> workspaceIds = JsonPath.read(workspaces.body(), "$[*].id");
        assertThat(workspaceIds).hasSize(3);

        HttpResponse<String> created = send(post("/api/bookings", aliceToken,
                "{\"workspaceId\":" + workspaceIds.get(1)
                        + ",\"startsAt\":\"2026-10-01T09:00:00Z\",\"endsAt\":\"2026-10-01T13:00:00Z\"}"));
        assertThat(created.statusCode()).isEqualTo(201);
        Integer bookingId = JsonPath.read(created.body(), "$.id");

        List<Integer> alices = JsonPath.read(send(get("/api/bookings", aliceToken)).body(), "$[*].id");
        List<Integer> bobs = JsonPath.read(send(get("/api/bookings", bobToken)).body(), "$[*].id");
        assertThat(alices).contains(bookingId);
        assertThat(bobs).doesNotContain(bookingId);

        // booking-service asks workspace-service, through a traced HTTP client, before it books.
        HttpResponse<String> unknownWorkspace = send(post("/api/bookings", aliceToken,
                "{\"workspaceId\":999,\"startsAt\":\"2026-10-01T09:00:00Z\",\"endsAt\":\"2026-10-01T13:00:00Z\"}"));
        assertThat(unknownWorkspace.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(2)
    void profileComesFromTheToken() throws Exception {
        HttpResponse<String> me = send(get("/api/users/me", bobToken));

        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(me.body(), "$.email")).isEqualTo("bob@coworking.test");
        assertThat(JsonPath.<List<String>>read(me.body(), "$.roles")).containsExactly("USER", "ADMIN");
    }

    @Test
    @Order(3)
    void requestsWithoutAValidTokenAreRejectedAtTheGateway() throws Exception {
        assertThat(send(get("/api/bookings", null)).statusCode()).isEqualTo(401);
        assertThat(send(get("/api/bookings", "not-a-jwt")).statusCode()).isEqualTo(401);
        assertThat(send(get("/api/bookings", aliceToken + "tampered")).statusCode()).isEqualTo(401);
    }

    @Test
    @Order(4)
    void aClientCannotClaimSomeoneElsesIdentityWithAHeader() throws Exception {
        HttpResponse<String> me = send(get("/api/users/me", aliceToken).header("X-Auth-User-Id", bobId));

        assertThat(JsonPath.<String>read(me.body(), "$.email")).isEqualTo("alice@coworking.test");
    }

    @Test
    @Order(5)
    void oneRequestIsOneTraceFromGatewayThroughServiceToDatabase() throws Exception {
        String traceId = HexFormat.of().formatHex(randomBytes(16));
        String callerSpanId = HexFormat.of().formatHex(randomBytes(8));
        HttpResponse<String> response = send(get("/api/bookings", aliceToken)
                .header("traceparent", "00-" + traceId + "-" + callerSpanId + "-01"));
        assertThat(response.statusCode()).isEqualTo(200);

        // Spans are exported in batches, a few seconds after the request.
        String jaeger = "http://" + STACK.getServiceHost("jaeger", 16686) + ":" + STACK.getServicePort("jaeger", 16686);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            HttpResponse<String> trace = HTTP.send(
                    HttpRequest.newBuilder(URI.create(jaeger + "/api/traces/" + traceId)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(trace.statusCode()).isEqualTo(200);
            List<String> services = JsonPath.read(trace.body(), "$.data[0].processes[*].serviceName");
            List<String> operations = JsonPath.read(trace.body(), "$.data[0].spans[*].operationName");
            assertThat(services).contains("gateway", "booking-service");
            assertThat(operations).contains("query");
        });
    }

    @Test
    @Order(6)
    void repeatedFailedLoginsAreThrottled() throws Exception {
        // 5 attempts per IP in a burst; two were spent in logIn().
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            response = send(post("/api/auth/login", null,
                    "{\"email\":\"alice@coworking.test\",\"password\":\"guess-" + attempt + "\"}"));
            if (response.statusCode() == 429) {
                break;
            }
            assertThat(response.statusCode()).isEqualTo(401);
        }

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).hasValue("12");
    }

    @Test
    @Order(7)
    void aStoppedServiceFailsOnlyItsOwnRoutes() throws Exception {
        ContainerState workspaceService = STACK.getContainerByServiceName("workspace-service")
                .orElseThrow(() -> new IllegalStateException("workspace-service container not found"));
        DockerClientFactory.instance().client().stopContainerCmd(workspaceService.getContainerId()).exec();

        HttpResponse<String> workspaces = send(get("/api/workspaces", aliceToken));
        assertThat(workspaces.statusCode()).isIn(502, 503);
        assertThat(workspaces.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));

        assertThat(send(get("/api/bookings", aliceToken)).statusCode()).isEqualTo(200);
    }

    private static String accessToken(String email) throws IOException, InterruptedException {
        HttpResponse<String> response = send(post("/api/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"password\"}"));
        assertThat(response.statusCode()).as("login of %s", email).isEqualTo(200);
        return JsonPath.read(response.body(), "$.access_token");
    }

    private static HttpRequest.Builder get(String path, String token) {
        return request(path, token).GET();
    }

    private static HttpRequest.Builder post(String path, String token, String json) {
        return request(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
    }

    private static HttpRequest.Builder request(String path, String token) {
        String gateway = "http://" + STACK.getServiceHost("gateway", 8080) + ":" + STACK.getServicePort("gateway", 8080);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(gateway + path)).timeout(Duration.ofSeconds(15));
        return token == null ? builder : builder.header("Authorization", "Bearer " + token);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }
}
