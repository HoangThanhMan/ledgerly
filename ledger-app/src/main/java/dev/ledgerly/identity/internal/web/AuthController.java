package dev.ledgerly.identity.internal.web;

import dev.ledgerly.identity.Role;
import dev.ledgerly.identity.internal.application.LoginResult;
import dev.ledgerly.identity.internal.application.RegisterResult;
import dev.ledgerly.identity.internal.application.UserService;
import dev.ledgerly.shared.problem.ProblemException;
import dev.ledgerly.shared.problem.ProblemResponses;
import dev.ledgerly.shared.problem.ProblemType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Sign up, and sign in for an access token.")
@RestController
@RequestMapping("/v1/auth")
class AuthController {

    /** BCrypt reads only this many bytes of a password: a longer one would match anything that shares them. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private static final String INVALID_CREDENTIALS = "Email or password is wrong";

    private final UserService users;

    AuthController(UserService users) {
        this.users = users;
    }

    @Operation(operationId = "register", summary = "Sign up", description = """
            Creates a user. Emails are unique regardless of case. The role defaults to USER. Signing up does not \
            sign in: call `login` next.""")
    @ApiResponse(responseCode = "201", description = "The user was created.")
    @ProblemResponses(ProblemType.EMAIL_TAKEN)
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ProblemException(
                    ProblemType.VALIDATION_ERROR, "password must be at most " + MAX_PASSWORD_BYTES + " bytes in UTF-8");
        }
        Role requested = request.role();
        Role role = requested != null ? requested : Role.USER;
        return switch (users.register(request.email(), request.password(), role)) {
            case RegisterResult.Registered registered -> UserResponse.from(registered.user());
            case RegisterResult.EmailTaken taken ->
                throw new ProblemException(ProblemType.EMAIL_TAKEN, "A user with email " + taken.email() + " exists");
        };
    }

    @Operation(operationId = "login", summary = "Sign in", description = """
            Exchanges email and password for an access token. A wrong password and an unknown email get the same \
            answer, so the API does not tell which emails are registered.""")
    @ApiResponse(responseCode = "200", description = "The access token.")
    @ProblemResponses(ProblemType.INVALID_CREDENTIALS)
    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return switch (users.login(request.email(), request.password())) {
            case LoginResult.LoggedIn loggedIn ->
                new TokenResponse(
                        loggedIn.accessToken(), "Bearer", loggedIn.expiresIn().toSeconds());
            case LoginResult.InvalidCredentials invalid ->
                throw new ProblemException(ProblemType.INVALID_CREDENTIALS, INVALID_CREDENTIALS);
        };
    }
}
