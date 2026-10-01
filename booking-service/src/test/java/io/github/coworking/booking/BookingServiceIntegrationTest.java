package io.github.coworking.booking;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.coworking.jwt.AuthHeaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against a real PostgreSQL with the production migrations; WireMock plays workspace-service. */
@SpringBootTest(properties = {
        "DB_PASSWORD=unused", // the datasource comes from the container
        "spring.zipkin.enabled=false",
})
@AutoConfigureMockMvc
@Testcontainers
class BookingServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    static final WireMockServer WORKSPACES = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WORKSPACES.start();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BookingRepository bookings;

    @DynamicPropertySource
    static void workspaceService(DynamicPropertyRegistry registry) {
        registry.add("services.workspace-service", WORKSPACES::baseUrl);
    }

    @AfterAll
    static void stopWorkspaces() {
        WORKSPACES.stop();
    }

    @BeforeEach
    void emptyTableAndKnownWorkspaces() {
        bookings.deleteAll();
        WORKSPACES.resetAll();
        WORKSPACES.stubFor(WireMock.get(urlMatching("/workspaces/[123]"))
                .willReturn(okJson("{\"id\":1,\"name\":\"Hub Podil\",\"address\":\"x\",\"capacity\":40}")));
    }

    @Test
    void createdBookingBelongsToTheCallerAndIsStored() throws Exception {
        create("7", 2, "2026-10-01T09:00:00Z", "2026-10-01T13:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.workspaceId").value(2))
                .andExpect(jsonPath("$.startsAt").value("2026-10-01T09:00:00Z"));

        assertThat(bookings.findAll()).singleElement().satisfies(stored -> {
            assertThat(stored.getUserId()).isEqualTo(7);
            assertThat(stored.getWorkspaceId()).isEqualTo(2);
        });
    }

    @Test
    void eachUserSeesOnlyTheirOwnBookingsInStartOrder() throws Exception {
        create("1", 1, "2026-10-02T09:00:00Z", "2026-10-02T10:00:00Z");
        create("1", 1, "2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z");
        create("2", 3, "2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z");

        mvc.perform(get("/bookings").header(AuthHeaders.USER_ID, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].startsAt").value("2026-10-01T09:00:00Z"))
                .andExpect(jsonPath("$[1].startsAt").value("2026-10-02T09:00:00Z"));

        mvc.perform(get("/bookings").header(AuthHeaders.USER_ID, "2"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].workspaceId").value(3));
    }

    @Test
    void bookingForAWorkspaceThatDoesNotExistIsRejected() throws Exception {
        WORKSPACES.stubFor(WireMock.get("/workspaces/99").willReturn(notFound()));

        create("1", 99, "2026-10-01T09:00:00Z", "2026-10-01T13:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Workspace 99 does not exist"));

        assertThat(bookings.count()).isZero();
    }

    @Test
    void bookingIsRefusedWhileWorkspacesCannotBeChecked() throws Exception {
        WORKSPACES.stubFor(WireMock.get("/workspaces/1").willReturn(serverError()));

        create("1", 1, "2026-10-01T09:00:00Z", "2026-10-01T13:00:00Z")
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(bookings.count()).isZero();
    }

    @Test
    void bookingThatEndsBeforeItStartsIsRejected() throws Exception {
        create("1", 1, "2026-10-01T13:00:00Z", "2026-10-01T09:00:00Z")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(bookings.count()).isZero();
    }

    @Test
    void missingFieldsAreRejected() throws Exception {
        mvc.perform(post("/bookings")
                        .header(AuthHeaders.USER_ID, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceId\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestThatDidNotComeThroughTheGatewayIsRejected() throws Exception {
        mvc.perform(get("/bookings")).andExpect(status().isBadRequest());
    }

    private ResultActions create(String userId, long workspaceId, String startsAt, String endsAt) throws Exception {
        return mvc.perform(post("/bookings")
                .header(AuthHeaders.USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspaceId\":" + workspaceId
                        + ",\"startsAt\":\"" + startsAt + "\",\"endsAt\":\"" + endsAt + "\"}"));
    }
}
