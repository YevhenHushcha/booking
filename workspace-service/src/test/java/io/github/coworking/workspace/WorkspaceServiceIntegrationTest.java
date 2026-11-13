package io.github.coworking.workspace;

import io.github.coworking.jwt.AuthHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against a real PostgreSQL with the production migrations and the demo seed data. */
@SpringBootTest(properties = {
        "DB_PASSWORD=unused", // the datasource comes from the container
        "spring.zipkin.enabled=false",
})
@AutoConfigureMockMvc
@Testcontainers
class WorkspaceServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void listsTheSeededWorkspacesInIdOrder() throws Exception {
        mvc.perform(get("/workspaces").header(AuthHeaders.USER_ID, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Hub Podil"))
                .andExpect(jsonPath("$[0].capacity").value(40))
                .andExpect(jsonPath("$[2].id").value(3));
    }

    @Test
    void returnsOneWorkspaceById() throws Exception {
        mvc.perform(get("/workspaces/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Loft Pechersk"));
    }

    @Test
    void unknownWorkspaceIsAProblemDetail404() throws Exception {
        mvc.perform(get("/workspaces/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Workspace 99 not found"));
    }
}
