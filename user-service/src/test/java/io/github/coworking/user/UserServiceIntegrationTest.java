package io.github.coworking.user;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.coworking.jwt.AuthHeaders;
import io.github.coworking.jwt.JwtContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs against a real PostgreSQL: Flyway applies the same migrations as in production, and
 * Hibernate's {@code ddl-auto: validate} fails the context if entity and schema disagree.
 */
@SpringBootTest(properties = {
        "JWT_SECRET=" + UserServiceIntegrationTest.JWT_SECRET,
        // The datasource comes from the container; the placeholder only has to resolve.
        "DB_PASSWORD=unused",
})
@AutoConfigureMockMvc
@Testcontainers
class UserServiceIntegrationTest {

    static final String JWT_SECRET = "user-service-test-secret-long-enough-for-hs256";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mvc;

    @Test
    void loginIssuesASignedTokenWithTheUsersIdentity() throws Exception {
        String body = login("bob@coworking.test", "password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900))
                .andReturn().getResponse().getContentAsString();

        SignedJWT jwt = SignedJWT.parse(JsonPath.read(body, "$.access_token"));
        assertThat(jwt.verify(new MACVerifier(JwtContract.signingKey(JWT_SECRET)))).isTrue();
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(JwtContract.ISSUER);
        assertThat(claims.getStringClaim(JwtContract.CLAIM_EMAIL)).isEqualTo("bob@coworking.test");
        assertThat(claims.getStringListClaim(JwtContract.CLAIM_ROLES)).containsExactly("USER", "ADMIN");
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        String wrongPassword = login("alice@coworking.test", "not-her-password")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody@coworking.test", "not-her-password")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Only "instance" could differ, and it is the same path: the bodies must be identical,
        // or the response would tell an attacker which emails are registered.
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void blankCredentialsAreRejectedAsBadRequest() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void meReturnsTheProfileOfTheUserTheGatewayIdentified() throws Exception {
        String token = login("alice@coworking.test", "password").andReturn().getResponse().getContentAsString();
        String aliceId = SignedJWT.parse(JsonPath.read(token, "$.access_token")).getJWTClaimsSet().getSubject();

        mvc.perform(get("/users/me").header(AuthHeaders.USER_ID, aliceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@coworking.test"))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                // The hash must never leave the service, not even under another name.
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString("$2a$"))));
    }

    @Test
    void meForAUserThatNoLongerExistsIsNotFound() throws Exception {
        mvc.perform(get("/users/me").header(AuthHeaders.USER_ID, "999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void meWithoutTheGatewayHeaderIsRejected() throws Exception {
        mvc.perform(get("/users/me")).andExpect(status().isBadRequest());
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }
}
