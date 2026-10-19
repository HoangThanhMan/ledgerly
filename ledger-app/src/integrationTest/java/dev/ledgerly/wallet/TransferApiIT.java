package dev.ledgerly.wallet;

import static dev.ledgerly.wallet.WalletApiDriver.IDEMPOTENCY_KEY;
import static dev.ledgerly.wallet.WalletApiDriver.body;
import static dev.ledgerly.wallet.WalletApiDriver.expectProblem;
import static dev.ledgerly.wallet.WalletApiDriver.items;
import static dev.ledgerly.wallet.WalletApiDriver.transferBody;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.ledger.LedgerApi;
import dev.ledgerly.ledger.Posting;
import dev.ledgerly.ledger.PostingRequest;
import dev.ledgerly.ledger.PostingResult;
import dev.ledgerly.ledger.SystemAccount;
import dev.ledgerly.ledger.TransactionType;
import dev.ledgerly.shared.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

class TransferApiIT extends AbstractIntegrationTest {

    private final RestTestClient client;
    private final LedgerApi ledger;
    private final WalletApiDriver api;

    private final JdbcClient jdbc;

    TransferApiIT(@Autowired RestTestClient client, @Autowired LedgerApi ledger, @Autowired JdbcClient jdbc) {
        this.client = client;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.api = new WalletApiDriver(client);
    }

    @Test
    void transferThatWouldOverflowTheTargetBalanceIsRejectedAndMovesNothing() {
        UUID source = api.openWalletWith(100);
        UUID target = walletHolding(Long.MAX_VALUE - 10);

        expectProblem(api.transfer(source, target, "11"), HttpStatus.UNPROCESSABLE_CONTENT, "balance-limit-exceeded")
                .jsonPath("$.detail")
                .isEqualTo("Wallet " + target + " cannot receive this amount: a balance would exceed the largest"
                        + " value the ledger can hold");
        assertThat(api.balanceOf(source)).isEqualTo("100");
        assertThat(api.balanceOf(target)).isEqualTo(Long.toString(Long.MAX_VALUE - 10));
        assertThat(api.entriesOf(source)).hasSize(1);

        // Up to the limit is fine.
        api.transfer(source, target, "10").expectStatus().isCreated();
        assertThat(api.balanceOf(target)).isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    @Test
    void depositThatWouldOverflowTheBalanceIsRejected() {
        UUID wallet = walletHolding(Long.MAX_VALUE - 10);

        expectProblem(api.deposit(wallet, "11", "VND"), HttpStatus.UNPROCESSABLE_CONTENT, "balance-limit-exceeded");
        assertThat(api.balanceOf(wallet)).isEqualTo(Long.toString(Long.MAX_VALUE - 10));
    }

    /**
     * A wallet with a balance no real deposit could build up. The money comes from a system account made for this
     * test: taking it from the shared funding account would leave that account unable to fund the other tests.
     */
    private UUID walletHolding(long balance) {
        UUID wallet = api.openWallet();
        UUID source = jdbc.sql("INSERT INTO accounts (type, code, currency, allow_negative)"
                        + " VALUES ('SYSTEM', :code, 'VND', TRUE) RETURNING id")
                .param("code", "test:" + UUID.randomUUID())
                .query(UUID.class)
                .single();
        Money amount = Money.of(balance, Currency.getInstance("VND"));
        PostingResult result = ledger.post(new PostingRequest(
                TransactionType.DEPOSIT,
                null,
                List.of(new Posting(source, amount.negate()), new Posting(wallet, amount))));
        assertThat(result).isInstanceOf(PostingResult.Posted.class);
        return wallet;
    }

    @Test
    void happyPath() {
        UUID source = api.openWalletWith(1_000);
        UUID target = api.openWallet();

        RestTestClient.ResponseSpec response = api.transfer(source, target, "300");

        response.expectStatus().isCreated();
        Map<String, Object> transfer = body(response);
        String id = (String) transfer.get("id");
        response.expectHeader().location("/v1/transfers/" + id);
        assertThat(transfer)
                .containsEntry("status", "COMPLETED")
                .containsEntry("sourceWalletId", source.toString())
                .containsEntry("targetWalletId", target.toString())
                .containsEntry("amount", "300")
                .containsEntry("currency", "VND")
                .containsKey("createdAt");

        assertThat(api.balanceOf(source)).isEqualTo("700");
        assertThat(api.balanceOf(target)).isEqualTo("300");
        assertThat(api.entriesOf(source).getFirst())
                .containsEntry("transactionId", id)
                .containsEntry("amount", "-300")
                .containsEntry("balanceAfter", "700")
                .containsEntry("currency", "VND");
        assertThat(api.entriesOf(target))
                .singleElement()
                .satisfies(entry -> assertThat(entry)
                        .containsEntry("transactionId", id)
                        .containsEntry("amount", "300")
                        .containsEntry("balanceAfter", "300"));
        assertThat(ledger.findTransaction(UUID.fromString(id)).orElseThrow().entries())
                .hasSize(2);
    }

    @Test
    void insufficientFunds() {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();

        RestTestClient.ResponseSpec response = api.transfer(source, target, "101");

        expectProblem(response, HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds")
                .jsonPath("$.detail")
                .isEqualTo("Wallet " + source + " has 100 VND, the transfer needs 101 VND");
        assertThat(api.balanceOf(source)).isEqualTo("100");
        assertThat(api.balanceOf(target)).isEqualTo("0");
        assertThat(api.entriesOf(source)).hasSize(1);
        assertThat(api.entriesOf(target)).isEmpty();
    }

    @Test
    void historyPagination() {
        UUID wallet = api.openWallet();
        for (int amount = 1; amount <= 5; amount++) {
            api.deposit(wallet, amount);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        @Nullable Object cursor = null;
        do {
            Map<String, Object> page = api.entriesPage(wallet, "?limit=2" + (cursor == null ? "" : "&after=" + cursor));
            entries.addAll(items(page));
            pageSizes.add(items(page).size());
            cursor = page.get("nextCursor");
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(entries).extracting(entry -> entry.get("amount")).containsExactly("5", "4", "3", "2", "1");
        assertThat(entries).extracting(entry -> entry.get("balanceAfter")).containsExactly("15", "10", "6", "3", "1");
        assertThat(entries)
                .extracting(entry -> Long.parseLong((String) entry.get("id")))
                .doesNotHaveDuplicates()
                .isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    void fullLastPageHasNoNextCursor() {
        UUID wallet = api.openWallet();
        api.deposit(wallet, 1);
        api.deposit(wallet, 2);

        Map<String, Object> page = api.entriesPage(wallet, "?limit=2");

        assertThat(items(page)).hasSize(2);
        assertThat(page.get("nextCursor")).isNull();
    }

    @Test
    void transferToTheSameWalletIsRejected() {
        UUID wallet = api.openWalletWith(100);

        expectProblem(api.transfer(wallet, wallet, "10"), HttpStatus.UNPROCESSABLE_CONTENT, "same-account-transfer");
        assertThat(api.entriesOf(wallet)).hasSize(1);
    }

    @Test
    void transferToAnUnknownWalletIsNotFoundAndMovesNothing() {
        UUID source = api.openWalletWith(100);
        UUID unknown = UUID.randomUUID();

        expectProblem(api.transfer(source, unknown, "10"), HttpStatus.NOT_FOUND, "wallet-not-found")
                .jsonPath("$.detail")
                .isEqualTo("Wallet " + unknown + " does not exist");
        assertThat(api.balanceOf(source)).isEqualTo("100");
    }

    @Test
    void systemAccountCannotBeTheSourceOfATransfer() {
        UUID funding = ledger.systemAccountId(SystemAccount.FUNDING);
        UUID target = api.openWallet();

        expectProblem(api.transfer(funding, target, "10"), HttpStatus.NOT_FOUND, "wallet-not-found");
        assertThat(api.balanceOf(target)).isEqualTo("0");
    }

    @Test
    void transferInAnotherCurrencyThanTheWalletsIsRejected() {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();

        expectProblem(api.transfer(source, target, "10", "USD"), HttpStatus.UNPROCESSABLE_CONTENT, "currency-mismatch");
        assertThat(api.balanceOf(source)).isEqualTo("100");
    }

    @Test
    void transferWithoutIdempotencyKeyIsAValidationErrorAndMovesNothing() {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();

        RestTestClient.ResponseSpec response = client.post()
                .uri("/v1/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .body(transferBody(source, target, "10", "VND"))
                .exchange();

        expectProblem(response, HttpStatus.BAD_REQUEST, "validation-error");
        assertThat(api.balanceOf(source)).isEqualTo("100");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "has space", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void malformedIdempotencyKeyIsAValidationError(String key) {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();

        RestTestClient.ResponseSpec response = client.post()
                .uri("/v1/transfers")
                .header(IDEMPOTENCY_KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(transferBody(source, target, "10", "VND"))
                .exchange();

        expectProblem(response, HttpStatus.BAD_REQUEST, "validation-error");
        assertThat(api.balanceOf(source)).isEqualTo("100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "+5", "1.5", "007", "abc", "", "1000000000000000000"})
    void amountThatIsNotAPositiveIntegerOfMinorUnitsIsAValidationError(String amount) {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();

        expectProblem(api.transfer(source, target, amount), HttpStatus.BAD_REQUEST, "validation-error")
                .jsonPath("$.errors[0].field")
                .isEqualTo("amount");
        assertThat(api.balanceOf(source)).isEqualTo("100");
    }

    @Test
    void transferCanBeReadBack() {
        UUID source = api.openWalletWith(500);
        UUID target = api.openWallet();
        Map<String, Object> created =
                body(api.transfer(source, target, "200").expectStatus().isCreated());

        Map<String, Object> readBack = body(client.get()
                .uri("/v1/transfers/{id}", created.get("id"))
                .exchange()
                .expectStatus()
                .isOk());

        assertThat(readBack).isEqualTo(created);
    }

    @Test
    void unknownTransferIsNotFound() {
        expectProblem(
                client.get().uri("/v1/transfers/{id}", UUID.randomUUID()).exchange(),
                HttpStatus.NOT_FOUND,
                "transfer-not-found");
    }

    @Test
    void depositIsNotReadableAsATransfer() {
        UUID wallet = api.openWallet();
        Map<String, Object> deposit =
                body(api.deposit(wallet, "50", "VND").expectStatus().isCreated());

        expectProblem(
                client.get().uri("/v1/transfers/{id}", deposit.get("id")).exchange(),
                HttpStatus.NOT_FOUND,
                "transfer-not-found");
    }

    @Test
    void depositCreditsTheWalletFromTheFundingAccount() {
        UUID wallet = api.openWallet();

        Map<String, Object> deposit =
                body(api.deposit(wallet, "250", "VND").expectStatus().isCreated());

        assertThat(deposit)
                .containsEntry("status", "COMPLETED")
                .containsEntry("walletId", wallet.toString())
                .containsEntry("amount", "250")
                .containsEntry("currency", "VND")
                .containsKeys("id", "createdAt");
        assertThat(api.balanceOf(wallet)).isEqualTo("250");
        assertThat(api.entriesOf(wallet))
                .singleElement()
                .satisfies(entry -> assertThat(entry)
                        .containsEntry("transactionId", deposit.get("id"))
                        .containsEntry("amount", "250"));
    }

    @Test
    void depositToAnUnknownWalletIsNotFound() {
        expectProblem(api.deposit(UUID.randomUUID(), "10", "VND"), HttpStatus.NOT_FOUND, "wallet-not-found");
    }

    @Test
    void depositInAnotherCurrencyThanTheWalletsIsRejected() {
        UUID wallet = api.openWallet();

        expectProblem(api.deposit(wallet, "10", "USD"), HttpStatus.UNPROCESSABLE_CONTENT, "currency-mismatch");
        assertThat(api.balanceOf(wallet)).isEqualTo("0");
    }

    @Test
    void depositWithoutIdempotencyKeyIsAValidationError() {
        UUID wallet = api.openWallet();

        RestTestClient.ResponseSpec response = client.post()
                .uri("/v1/admin/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("walletId", wallet, "amount", "10", "currency", "VND"))
                .exchange();

        expectProblem(response, HttpStatus.BAD_REQUEST, "validation-error");
        assertThat(api.balanceOf(wallet)).isEqualTo("0");
    }
}
