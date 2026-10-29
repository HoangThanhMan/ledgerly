package dev.ledgerly.identity;

import static dev.ledgerly.wallet.WalletApiDriver.body;
import static dev.ledgerly.wallet.WalletApiDriver.expectProblem;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

class AuthApiIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse";

    private final RestTestClient client;

    AuthApiIT(@Autowired RestTestClient client) {
        this.client = client;
    }

    @Test
    void registerReturnsTheUser() {
        String email = uniqueEmail("An");

        RestTestClient.ResponseSpec response =
                register(Map.of("email", email, "password", PASSWORD, "role", "ORGANIZER"));

        response.expectStatus().isCreated();
        Map<String, Object> user = body(response);
        assertThat(user)
                .containsEntry("email", email.toLowerCase(Locale.ROOT))
                .containsEntry("role", "ORGANIZER")
                .containsKey("id")
                .doesNotContainKeys("password", "passwordHash");
    }

    @Test
    void roleDefaultsToUser() {
        Map<String, Object> user = body(register(Map.of("email", uniqueEmail("user"), "password", PASSWORD))
                .expectStatus()
                .isCreated());

        assertThat(user).containsEntry("role", "USER");
    }

    @Test
    void emailIsCaseInsensitivelyUnique() {
        String email = uniqueEmail("a");
        register(Map.of("email", email, "password", PASSWORD)).expectStatus().isCreated();

        expectProblem(
                register(Map.of("email", email.toUpperCase(Locale.ROOT), "password", PASSWORD)),
                HttpStatus.CONFLICT,
                "email-taken");
    }

    @Test
    void shortPasswordIsInvalid() {
        expectProblem(
                register(Map.of("email", uniqueEmail("short"), "password", "1234567")),
                HttpStatus.BAD_REQUEST,
                "validation-error");
    }

    /** BCrypt reads only the first 72 bytes: a longer password would match anything sharing those bytes. */
    @Test
    void passwordLongerThan72BytesIsInvalid() {
        String fortyCharsOf120Bytes = "ệ".repeat(40);

        expectProblem(
                register(Map.of("email", uniqueEmail("long"), "password", fortyCharsOf120Bytes)),
                HttpStatus.BAD_REQUEST,
                "validation-error");
    }

    @Test
    void loginReturnsABearerToken() {
        String email = uniqueEmail("login");
        register(Map.of("email", email, "password", PASSWORD)).expectStatus().isCreated();

        Map<String, Object> token = body(
                login(email.toUpperCase(Locale.ROOT), PASSWORD).expectStatus().isOk());

        assertThat(token).containsEntry("tokenType", "Bearer").containsEntry("expiresIn", 3600);
        assertThat((String) token.get("accessToken")).isNotBlank();
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() {
        String email = uniqueEmail("known");
        register(Map.of("email", email, "password", PASSWORD)).expectStatus().isCreated();

        String wrongPassword = rawProblem(login(email, "wrong-password"));
        String unknownEmail = rawProblem(login(uniqueEmail("unknown"), PASSWORD));

        assertThat(wrongPassword).isEqualTo(unknownEmail);
    }

    private static String rawProblem(RestTestClient.ResponseSpec response) {
        byte[] body = expectProblem(response, HttpStatus.UNAUTHORIZED, "invalid-credentials")
                .returnResult()
                .getResponseBody();
        return new String(requireNonNull(body), StandardCharsets.UTF_8);
    }

    private RestTestClient.ResponseSpec register(Map<String, Object> request) {
        return post("/v1/auth/register", request);
    }

    private RestTestClient.ResponseSpec login(String email, String password) {
        return post("/v1/auth/login", Map.of("email", email, "password", password));
    }

    private RestTestClient.ResponseSpec post(String path, Map<String, Object> request) {
        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange();
    }

    private static String uniqueEmail(String name) {
        return name + "-" + UUID.randomUUID() + "@Example.com";
    }
}
