package io.github.coworking.user.account;

import io.github.coworking.jwt.AuthHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    /** The caller's own profile; the id comes from the header the gateway sets after verifying the token. */
    @GetMapping("/me")
    public UserResponse me(@RequestHeader(AuthHeaders.USER_ID) long userId) {
        return users.findById(userId)
                .map(user -> new UserResponse(user.getId(), user.getEmail(), user.getRoles()))
                // A valid token for a user deleted after it was issued.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User no longer exists"));
    }

    public record UserResponse(long id, String email, List<String> roles) {
    }
}
