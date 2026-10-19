package dev.ledgerly.wallet;

import static dev.ledgerly.wallet.WalletApiDriver.body;
import static dev.ledgerly.wallet.WalletApiDriver.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.LedgerApi;
import dev.ledgerly.ledger.SystemAccount;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

class WalletApiIT extends AbstractIntegrationTest {

    private final RestTestClient client;
    private final LedgerApi ledger;
    private final WalletApiDriver api;

    WalletApiIT(@Autowired RestTestClient client, @Autowired LedgerApi ledger) {
        this.client = client;
        this.ledger = ledger;
        this.api = new WalletApiDriver(client);
    }

    @Test
    void openedWalletIsEmptyAndCanBeReadBack() {
        RestTestClient.ResponseSpec response = client.post()
                .uri("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currency", "VND"))
                .exchange();

        response.expectStatus().isCreated();
        Map<String, Object> wallet = body(response);
        assertThat(wallet).containsEntry("currency", "VND").containsEntry("balance", "0");
        assertThat(Instant.parse((String) wallet.get("createdAt"))).isBeforeOrEqualTo(Instant.now());
        String id = (String) wallet.get("id");
        response.expectHeader().location("/v1/wallets/" + id);

        Map<String, Object> readBack = body(client.get()
                .uri("/v1/wallets/{id}", id)
                .exchange()
                .expectStatus()
                .isOk());
        assertThat(readBack).isEqualTo(wallet);
    }

    @Test
    void walletShowsItsBalanceAsAStringOfMinorUnits() {
        UUID wallet = api.openWalletWith(150_000);

        client.get()
                .uri("/v1/wallets/{id}", wallet)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.balance")
                .isEqualTo("150000");
    }

    @Test
    void unknownWalletIsAProblemWithTheRequestPathAsInstance() {
        UUID unknown = UUID.randomUUID();

        expectProblem(
                        client.get().uri("/v1/wallets/{id}", unknown).exchange(),
                        HttpStatus.NOT_FOUND,
                        "wallet-not-found")
                .jsonPath("$.detail")
                .isEqualTo("Wallet " + unknown + " does not exist")
                .jsonPath("$.instance")
                .isEqualTo("/v1/wallets/" + unknown);
    }

    @Test
    void systemAccountsAreNotExposedAsWallets() {
        UUID funding = ledger.systemAccountId(SystemAccount.FUNDING);

        expectProblem(
                client.get().uri("/v1/wallets/{id}", funding).exchange(), HttpStatus.NOT_FOUND, "wallet-not-found");
        expectProblem(
                client.get().uri("/v1/wallets/{id}/entries", funding).exchange(),
                HttpStatus.NOT_FOUND,
                "wallet-not-found");
    }

    @Test
    void walletInACurrencyWithoutSystemAccountsIsRejected() {
        RestTestClient.ResponseSpec response = client.post()
                .uri("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("currency", "USD"))
                .exchange();

        expectProblem(response, HttpStatus.UNPROCESSABLE_CONTENT, "unsupported-currency");
    }

    @Test
    void missingCurrencyIsAValidationErrorNamingTheField() {
        RestTestClient.ResponseSpec response = client.post()
                .uri("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .exchange();

        expectProblem(response, HttpStatus.BAD_REQUEST, "validation-error")
                .jsonPath("$.errors[0].field")
                .isEqualTo("currency");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"currency\": \"XXXX\"}", "{\"currency\": ", "[]"})
    void unreadableBodyIsAValidationError(String json) {
        RestTestClient.ResponseSpec response = client.post()
                .uri("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange();

        expectProblem(response, HttpStatus.BAD_REQUEST, "validation-error");
    }

    @Test
    void malformedWalletIdIsAValidationError() {
        expectProblem(
                client.get().uri("/v1/wallets/not-a-uuid").exchange(), HttpStatus.BAD_REQUEST, "validation-error");
    }

    @ParameterizedTest
    @ValueSource(strings = {"?limit=0", "?limit=101", "?limit=abc", "?after=0", "?after=abc"})
    void invalidPagingParametersAreValidationErrors(String query) {
        UUID wallet = api.openWallet();

        expectProblem(
                client.get().uri("/v1/wallets/{id}/entries" + query, wallet).exchange(),
                HttpStatus.BAD_REQUEST,
                "validation-error");
    }

    @Test
    void newWalletHasNoEntries() {
        Map<String, Object> page = api.entriesPage(api.openWallet(), "");

        assertThat(WalletApiDriver.items(page)).isEmpty();
        assertThat(page.get("nextCursor")).isNull();
    }
}
