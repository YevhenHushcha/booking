package io.github.coworking.user.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.coworking.jwt.TokenIssuer;
import io.github.coworking.user.account.User;
import io.github.coworking.user.account.UserRepository;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    /** Checked when the email is unknown, so both failure paths cost one BCrypt comparison. */
    private final String dummyHash;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder, TokenIssuer tokenIssuer) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        Optional<User> user = users.findByEmail(request.getEmail());
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), hash);
        if (!user.isPresent() || !passwordMatches) {
            // One message for both cases: the response must not reveal which emails are registered.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        User found = user.get();
        TokenIssuer.IssuedToken token = tokenIssuer.issue(found.getId(), found.getEmail(), found.getRoles());
        long expiresIn = Duration.between(token.getIssuedAt(), token.getExpiresAt()).getSeconds();
        return new TokenResponse(token.getValue(), "Bearer", expiresIn);
    }

    public static class LoginRequest {

        @NotBlank
        private String email;

        @NotBlank
        private String password;

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /** Field names follow the OAuth 2.0 token response (RFC 6749, section 5.1). */
    public static class TokenResponse {

        @JsonProperty("access_token")
        private final String accessToken;

        @JsonProperty("token_type")
        private final String tokenType;

        @JsonProperty("expires_in")
        private final long expiresIn;

        TokenResponse(String accessToken, String tokenType, long expiresIn) {
            this.accessToken = accessToken;
            this.tokenType = tokenType;
            this.expiresIn = expiresIn;
        }
    }
}
