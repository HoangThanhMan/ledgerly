package dev.ledgerly.identity;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Signs up test users and hands out clients that send their access token with every request. */
public final class AuthDriver {

    private static final String PASSWORD = "integration-test-password";

    private AuthDriver() {}

    /** A client of a new organizer: the role that may do everything the wallet API offers. */
    public static RestTestClient signedIn(RestTestClient client) {
        return signedIn(client, Role.ORGANIZER);
    }

    /** A client of a new user with the given role. */
    public static RestTestClient signedIn(RestTestClient client, Role role) {
        return withToken(client, register(client, role));
    }

    public static RestTestClient withToken(RestTestClient client, String token) {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    /** Signs up a user with a unique email and signs them in. */
    public static String register(RestTestClient client, Role role) {
        String email = role.name().toLowerCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID() + "@test.ledgerly.dev";
        client.post()
                .uri("/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", PASSWORD, "role", role.name()))
                .exchange()
                .expectStatus()
                .isCreated();
        Map<String, Object> token = requireNonNull(client.post()
                .uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", PASSWORD))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult()
                .getResponseBody());
        return (String) requireNonNull(token.get("accessToken"));
    }
}
