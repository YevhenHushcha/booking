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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * <p>
 * Java 8 has no HTTP client beyond {@link HttpURLConnection}; {@link Request} wraps the little of it
 * these tests need.
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
            .withEnv(testEnvironment())
            // Grafana and Prometheus only display data; nothing here asserts on them.
            .withServices("gateway", "user-service", "workspace-service", "booking-service", "jaeger")
            .withExposedService("gateway", 8080, Wait.forHealthcheck().withStartupTimeout(STARTUP))
            .withExposedService("jaeger", 16686, Wait.forListeningPort().withStartupTimeout(STARTUP))
            .waitingFor("user-service", Wait.forHealthcheck().withStartupTimeout(STARTUP))
            .waitingFor("workspace-service", Wait.forHealthcheck().withStartupTimeout(STARTUP))
            .waitingFor("booking-service", Wait.forHealthcheck().withStartupTimeout(STARTUP));

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
        Response workspaces = send(get("/api/workspaces", aliceToken));
        assertThat(workspaces.statusCode()).isEqualTo(200);
        List<Integer> workspaceIds = JsonPath.read(workspaces.body(), "$[*].id");
        assertThat(workspaceIds).hasSize(3);

        Response created = send(post("/api/bookings", aliceToken,
                "{\"workspaceId\":" + workspaceIds.get(1)
                        + ",\"startsAt\":\"2026-10-01T09:00:00Z\",\"endsAt\":\"2026-10-01T13:00:00Z\"}"));
        assertThat(created.statusCode()).isEqualTo(201);
        Integer bookingId = JsonPath.read(created.body(), "$.id");

        List<Integer> alices = JsonPath.read(send(get("/api/bookings", aliceToken)).body(), "$[*].id");
        List<Integer> bobs = JsonPath.read(send(get("/api/bookings", bobToken)).body(), "$[*].id");
        assertThat(alices).contains(bookingId);
        assertThat(bobs).doesNotContain(bookingId);

        // booking-service asks workspace-service, through a traced HTTP client, before it books.
        Response unknownWorkspace = send(post("/api/bookings", aliceToken,
                "{\"workspaceId\":999,\"startsAt\":\"2026-10-01T09:00:00Z\",\"endsAt\":\"2026-10-01T13:00:00Z\"}"));
        assertThat(unknownWorkspace.statusCode()).isEqualTo(422);
    }

    @Test
    @Order(2)
    void profileComesFromTheToken() throws Exception {
        Response me = send(get("/api/users/me", bobToken));

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
        Response me = send(get("/api/users/me", aliceToken).header("X-Auth-User-Id", bobId));

        assertThat(JsonPath.<String>read(me.body(), "$.email")).isEqualTo("alice@coworking.test");
    }

    @Test
    @Order(5)
    void oneRequestIsOneTraceFromGatewayThroughServiceToDatabase() throws Exception {
        String traceId = hex(randomBytes(16));
        String callerSpanId = hex(randomBytes(8));
        // Sleuth reads Zipkin's B3 format; the single-header form is traceId-spanId-sampled.
        Response response = send(get("/api/bookings", aliceToken)
                .header("b3", traceId + "-" + callerSpanId + "-1"));
        assertThat(response.statusCode()).isEqualTo(200);

        // Spans are exported in batches, a few seconds after the request.
        String jaeger = "http://" + STACK.getServiceHost("jaeger", 16686) + ":" + STACK.getServicePort("jaeger", 16686);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            Response trace = send(new Request("GET", jaeger + "/api/traces/" + traceId));
            assertThat(trace.statusCode()).isEqualTo(200);
            List<String> services = JsonPath.read(trace.body(), "$.data[0].processes[*].serviceName");
            List<String> operations = JsonPath.read(trace.body(), "$.data[0].spans[*].operationName");
            assertThat(services).contains("gateway", "booking-service");
            // Sleuth names a JDBC span after the statement type.
            assertThat(operations).contains("select");
        });
    }

    @Test
    @Order(6)
    void repeatedFailedLoginsAreThrottled() throws Exception {
        // 5 attempts per IP in a burst; two were spent in logIn().
        Response response = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            response = send(post("/api/auth/login", null,
                    "{\"email\":\"alice@coworking.test\",\"password\":\"guess-" + attempt + "\"}"));
            if (response.statusCode() == 429) {
                break;
            }
            assertThat(response.statusCode()).isEqualTo(401);
        }

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.header("Retry-After")).isEqualTo("12");
    }

    @Test
    @Order(7)
    void aStoppedServiceFailsOnlyItsOwnRoutes() throws Exception {
        ContainerState workspaceService = STACK.getContainerByServiceName("workspace-service")
                .orElseThrow(() -> new IllegalStateException("workspace-service container not found"));
        DockerClientFactory.instance().client().stopContainerCmd(workspaceService.getContainerId()).exec();

        Response workspaces = send(get("/api/workspaces", aliceToken));
        assertThat(workspaces.statusCode()).isIn(502, 503);
        assertThat(workspaces.header("Content-Type")).startsWith("application/problem+json");

        assertThat(send(get("/api/bookings", aliceToken)).statusCode()).isEqualTo(200);
    }

    private static String accessToken(String email) throws IOException {
        Response response = send(post("/api/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"password\"}"));
        assertThat(response.statusCode()).as("login of %s", email).isEqualTo(200);
        return JsonPath.read(response.body(), "$.access_token");
    }

    private static Request get(String path, String token) {
        return request("GET", path, token);
    }

    private static Request post(String path, String token, String json) {
        return request("POST", path, token)
                .header("Content-Type", "application/json")
                .body(json);
    }

    private static Request request(String method, String path, String token) {
        String gateway = "http://" + STACK.getServiceHost("gateway", 8080) + ":" + STACK.getServicePort("gateway", 8080);
        Request request = new Request(method, gateway + path);
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private static Response send(Request request) throws IOException {
        return request.send();
    }

    private static Map<String, String> testEnvironment() {
        // Test values, independent of anyone's .env; environment variables take precedence over it.
        Map<String, String> env = new HashMap<>();
        env.put("POSTGRES_PASSWORD", "e2e-postgres");
        env.put("DB_PASSWORD", "e2e-app");
        env.put("JWT_SECRET", "e2e-secret-that-is-long-enough-for-hs256");
        env.put("GRAFANA_ADMIN_PASSWORD", "unused");
        return env;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static final class Request {

        private static final int TIMEOUT_MILLIS = (int) Duration.ofSeconds(15).toMillis();

        private final String method;
        private final String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String body;

        Request(String method, String url) {
            this.method = method;
            this.url = url;
        }

        Request header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        Request body(String body) {
            this.body = body;
            return this;
        }

        Response send() throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setRequestMethod(method);
                connection.setConnectTimeout(TIMEOUT_MILLIS);
                connection.setReadTimeout(TIMEOUT_MILLIS);
                headers.forEach(connection::setRequestProperty);
                if (body != null) {
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int status = connection.getResponseCode();
                // 4xx and 5xx bodies come from the error stream; getInputStream() would throw.
                InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                Map<String, String> responseHeaders = new HashMap<>();
                connection.getHeaderFields().forEach((name, values) -> {
                    if (name != null && !values.isEmpty()) {
                        responseHeaders.put(name.toLowerCase(), values.get(0));
                    }
                });
                return new Response(status, responseHeaders, in == null ? "" : read(in));
            } finally {
                connection.disconnect();
            }
        }

        private static String read(InputStream in) throws IOException {
            try (InputStream input = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = input.read(buffer)) != -1; ) {
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private static final class Response {

        private final int statusCode;
        private final Map<String, String> headers;
        private final String body;

        Response(int statusCode, Map<String, String> headers, String body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        int statusCode() {
            return statusCode;
        }

        String body() {
            return body;
        }

        /** First value of the header, or null; names are case-insensitive. */
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }
}
