package io.github.coworking.user.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.coworking.jwt.TokenIssuer;
import io.github.coworking.user.account.User;
import io.github.coworking.user.account.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
        Optional<User> user = users.findByEmail(request.email());
        String hash = user.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !passwordMatches) {
            // One message for both cases: the response must not reveal which emails are registered.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        User found = user.get();
        TokenIssuer.IssuedToken token = tokenIssuer.issue(found.getId(), found.getEmail(), found.getRoles());
        long expiresIn = Duration.between(token.issuedAt(), token.expiresAt()).toSeconds();
        return new TokenResponse(token.value(), "Bearer", expiresIn);
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    /** Field names follow the OAuth 2.0 token response (RFC 6749, section 5.1). */
    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
