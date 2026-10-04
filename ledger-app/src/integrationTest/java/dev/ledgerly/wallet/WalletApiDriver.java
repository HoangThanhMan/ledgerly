package dev.ledgerly.wallet;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Calls the wallet API the way a client would, to arrange test data and read results back. */
final class WalletApiDriver {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private final RestTestClient client;

    WalletApiDriver(RestTestClient client) {
        this.client = client;
    }

    UUID openWallet() {
        Map<String, Object> wallet = body(client.post()
                .uri("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currency", "VND"))
                .exchange()
                .expectStatus()
                .isCreated());
        return UUID.fromString((String) requireNonNull(wallet.get("id")));
    }

    UUID openWalletWith(long balance) {
        UUID wallet = openWallet();
        deposit(wallet, balance);
        return wallet;
    }

    void deposit(UUID wallet, long amount) {
        deposit(wallet, Long.toString(amount), "VND").expectStatus().isCreated();
    }

    RestTestClient.ResponseSpec deposit(UUID wallet, String amount, String currency) {
        return client.post()
                .uri("/v1/admin/deposits")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("walletId", wallet, "amount", amount, "currency", currency))
                .exchange();
    }

    RestTestClient.ResponseSpec transfer(UUID source, UUID target, String amount) {
        return transfer(source, target, amount, "VND");
    }

    RestTestClient.ResponseSpec transfer(UUID source, UUID target, String amount, String currency) {
        return client.post()
                .uri("/v1/transfers")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(transferBody(source, target, amount, currency))
                .exchange();
    }

    static Map<String, Object> transferBody(UUID source, UUID target, String amount, String currency) {
        return Map.of("sourceWalletId", source, "targetWalletId", target, "amount", amount, "currency", currency);
    }

    String balanceOf(UUID wallet) {
        Map<String, Object> body = body(client.get()
                .uri("/v1/wallets/{id}", wallet)
                .exchange()
                .expectStatus()
                .isOk());
        return (String) requireNonNull(body.get("balance"));
    }

    /** Entries of a wallet, newest first. Fetches a single page of up to 100 entries. */
    List<Map<String, Object>> entriesOf(UUID wallet) {
        return items(entriesPage(wallet, "?limit=100"));
    }

    Map<String, Object> entriesPage(UUID wallet, String query) {
        return body(client.get()
                .uri("/v1/wallets/{id}/entries" + query, wallet)
                .exchange()
                .expectStatus()
                .isOk());
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) requireNonNull(page.get("items"));
    }

    static Map<String, Object> body(RestTestClient.ResponseSpec response) {
        return requireNonNull(response.expectBody(JSON_OBJECT).returnResult().getResponseBody());
    }

    /** Asserts an RFC 9457 response of the given problem type and returns its body for further checks. */
    static RestTestClient.BodyContentSpec expectProblem(
            RestTestClient.ResponseSpec response, HttpStatus status, String type) {
        return response.expectStatus()
                .isEqualTo(status)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo("/problems/" + type)
                .jsonPath("$.status")
                .isEqualTo(status.value())
                .jsonPath("$.title")
                .isNotEmpty();
    }
}
