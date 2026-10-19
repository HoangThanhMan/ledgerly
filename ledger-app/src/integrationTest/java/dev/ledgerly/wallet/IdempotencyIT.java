package dev.ledgerly.wallet;

import static dev.ledgerly.wallet.WalletApiDriver.IDEMPOTENT_REPLAYED;
import static dev.ledgerly.wallet.WalletApiDriver.body;
import static dev.ledgerly.wallet.WalletApiDriver.expectProblem;
import static dev.ledgerly.wallet.WalletApiDriver.rawBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.ledgerly.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/** What a client sees when it sends a money-moving request more than once under the same {@code Idempotency-Key}. */
class IdempotencyIT extends AbstractIntegrationTest {

    private final RestTestClient client;
    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final MeterRegistry meters;
    private final WalletApiDriver api;

    IdempotencyIT(
            @Autowired RestTestClient client,
            @Autowired DataSource dataSource,
            @Autowired JdbcClient jdbc,
            @Autowired MeterRegistry meters) {
        this.client = client;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.meters = meters;
        this.api = new WalletApiDriver(client);
    }

    @Test
    void retryOfACompletedTransferReplaysTheFirstResponseAndMovesNoMoreMoney() {
        UUID source = api.openWalletWith(1_000);
        UUID target = api.openWallet();
        String key = newKey();
        double replaysBefore = replays();

        RestTestClient.ResponseSpec first = api.transfer(key, source, target, "300");
        RestTestClient.ResponseSpec retry = api.transfer(key, source, target, "300");

        first.expectStatus().isCreated().expectHeader().doesNotExist(IDEMPOTENT_REPLAYED);
        String firstBody = rawBody(first);
        String transferId =
                (String) body(api.transfer(key, source, target, "300")).get("id");
        retry.expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals(IDEMPOTENT_REPLAYED, "true")
                .expectHeader()
                .location("/v1/transfers/" + transferId)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        first.expectHeader().location("/v1/transfers/" + transferId);
        assertThat(rawBody(retry)).isEqualTo(firstBody);

        assertThat(api.balanceOf(source)).isEqualTo("700");
        assertThat(api.balanceOf(target)).isEqualTo("300");
        assertThat(api.entriesOf(target)).hasSize(1);
        assertThat(replays() - replaysBefore).isEqualTo(2);
    }

    @Test
    void replayedTransferIsTheOneThatCanBeReadBack() {
        UUID source = api.openWalletWith(500);
        UUID target = api.openWallet();
        String key = newKey();
        api.transfer(key, source, target, "200").expectStatus().isCreated();

        Map<String, Object> replayed = body(api.transfer(key, source, target, "200"));

        Map<String, Object> readBack = body(client.get()
                .uri("/v1/transfers/{id}", replayed.get("id"))
                .exchange()
                .expectStatus()
                .isOk());
        assertThat(replayed).isEqualTo(readBack);
    }

    @Test
    void sameKeyDifferentBody() {
        UUID source = api.openWalletWith(1_000);
        UUID target = api.openWallet();
        String key = newKey();
        api.transfer(key, source, target, "300").expectStatus().isCreated();

        RestTestClient.ResponseSpec response = api.transfer(key, source, target, "301");

        expectProblem(response, HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused");
        response.expectHeader().doesNotExist(IDEMPOTENT_REPLAYED);
        assertThat(api.balanceOf(source)).isEqualTo("700");
        // The key still answers the request it belongs to.
        api.transfer(key, source, target, "300").expectStatus().isCreated();
        assertThat(api.balanceOf(source)).isEqualTo("700");
    }

    @Test
    void sameKeyOnAnotherEndpoint() {
        UUID wallet = api.openWallet();
        UUID target = api.openWallet();
        String key = newKey();
        api.deposit(key, wallet, "100", "VND").expectStatus().isCreated();

        expectProblem(
                api.transfer(key, wallet, target, "100"), HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused");

        assertThat(api.balanceOf(wallet)).isEqualTo("100");
    }

    @Test
    void missingKey() {
        UUID wallet = api.openWallet();

        RestTestClient.ResponseSpec response = client.post()
                .uri("/v1/admin/deposits")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("walletId", wallet, "amount", "100", "currency", "VND"))
                .exchange();

        expectProblem(response, HttpStatus.BAD_REQUEST, "validation-error")
                .jsonPath("$.errors[0].field")
                .doesNotExist()
                .jsonPath("$.detail")
                .isEqualTo("Required header 'Idempotency-Key' is not present.");
        assertThat(api.balanceOf(wallet)).isEqualTo("0");
    }

    @Test
    void replayInsufficientFunds() {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();
        String key = newKey();

        RestTestClient.ResponseSpec first = api.transfer(key, source, target, "101");
        // The wallet could afford the transfer now, but the key already has its answer.
        api.deposit(source, 1_000);
        RestTestClient.ResponseSpec retry = api.transfer(key, source, target, "101");

        expectProblem(first, HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds");
        first.expectHeader().doesNotExist(IDEMPOTENT_REPLAYED);
        expectProblem(retry, HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds");
        retry.expectHeader().valueEquals(IDEMPOTENT_REPLAYED, "true");
        assertThat(rawBody(retry)).isEqualTo(rawBody(first));
        assertThat(body(retry))
                .containsOnlyKeys("type", "title", "status", "detail", "instance")
                .containsEntry("detail", "Wallet " + source + " has 100 VND, the transfer needs 101 VND")
                .containsEntry("instance", "/v1/transfers");
        assertThat(api.balanceOf(source)).isEqualTo("1100");
        assertThat(api.entriesOf(target)).isEmpty();
    }

    @Test
    void rejectionsThatDependOnlyOnTheRequestAreStoredToo() {
        UUID wallet = api.openWalletWith(100);
        String key = newKey();

        expectProblem(
                api.transfer(key, wallet, wallet, "10"), HttpStatus.UNPROCESSABLE_CONTENT, "same-account-transfer");
        RestTestClient.ResponseSpec retry = api.transfer(key, wallet, wallet, "10");

        expectProblem(retry, HttpStatus.UNPROCESSABLE_CONTENT, "same-account-transfer");
        retry.expectHeader().valueEquals(IDEMPOTENT_REPLAYED, "true");
    }

    @Test
    void depositIsCreditedOncePerKey() {
        UUID wallet = api.openWallet();
        String key = newKey();

        RestTestClient.ResponseSpec first = api.deposit(key, wallet, "250", "VND");
        RestTestClient.ResponseSpec retry = api.deposit(key, wallet, "250", "VND");

        first.expectStatus().isCreated().expectHeader().doesNotExist(IDEMPOTENT_REPLAYED);
        retry.expectStatus().isCreated().expectHeader().valueEquals(IDEMPOTENT_REPLAYED, "true");
        assertThat(rawBody(retry)).isEqualTo(rawBody(first));
        assertThat(api.balanceOf(wallet)).isEqualTo("250");
        assertThat(api.entriesOf(wallet)).hasSize(1);
    }

    @Test
    void requestThatFailsValidationDoesNotUseUpTheKey() {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();
        String key = newKey();

        expectProblem(api.transfer(key, source, target, "1.5"), HttpStatus.BAD_REQUEST, "validation-error");

        api.transfer(key, source, target, "15").expectStatus().isCreated();
        assertThat(api.balanceOf(target)).isEqualTo("15");
    }

    @Test
    void requestThatArrivesWhileTheFirstIsStillRunningIsToldToRetry() throws Exception {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();
        String key = newKey();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            // The first request claims the key and then waits for this lock on the source wallet.
            lockRow(holder, source);
            CompletableFuture<RestTestClient.ResponseSpec> first =
                    CompletableFuture.supplyAsync(() -> api.transfer(key, source, target, "10"));
            await().atMost(Duration.ofSeconds(5)).until(() -> keyExists(key));

            RestTestClient.ResponseSpec second = api.transfer(key, source, target, "10");

            expectProblem(second, HttpStatus.CONFLICT, "idempotency-in-progress");
            second.expectHeader().valueEquals("Retry-After", "1");
            holder.rollback();
            first.get(10, TimeUnit.SECONDS).expectStatus().isCreated();
        }

        api.transfer(key, source, target, "10")
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals(IDEMPOTENT_REPLAYED, "true");
        assertThat(api.balanceOf(source)).isEqualTo("90");
        assertThat(api.balanceOf(target)).isEqualTo("10");
    }

    @Test
    void technicalFailureStoresNothingAndFreesTheKeyForAnImmediateRetry() throws Exception {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();
        String key = newKey();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockRow(holder, source);
            // The lock wait times out: the transfer is rolled back and answered with 503.
            expectProblem(api.transfer(key, source, target, "10"), HttpStatus.SERVICE_UNAVAILABLE, "overloaded");
            holder.rollback();
        }

        api.transfer(key, source, target, "10")
                .expectStatus()
                .isCreated()
                .expectHeader()
                .doesNotExist(IDEMPOTENT_REPLAYED);
        assertThat(api.balanceOf(target)).isEqualTo("10");
    }

    private boolean keyExists(String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE idem_key = :key")
                        .param("key", key)
                        .query(Long.class)
                        .single()
                == 1;
    }

    private double replays() {
        return meters.counter("ledgerly.idempotency.replays").count();
    }

    private static void lockRow(Connection connection, UUID accountId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT 1 FROM accounts WHERE id = ? FOR NO KEY UPDATE")) {
            statement.setObject(1, accountId);
            statement.execute();
        }
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
