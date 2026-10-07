package dev.ledgerly.wallet;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;

/**
 * A client that follows the retry protocol of the API: while the answer is 409, it waits as long as
 * {@code Retry-After} says and sends the same request again under the same key.
 */
final class RetryingClient {

    private static final int MAX_ATTEMPTS = 30;

    /**
     * The answer a client ends up with.
     *
     * @param conflicts how many times it was told to retry before
     */
    record Answer(int status, String body, boolean replayed, int conflicts) {}

    private final WalletApiDriver api;
    private final JdbcClient jdbc;

    RetryingClient(WalletApiDriver api, JdbcClient jdbc) {
        this.api = api;
        this.jdbc = jdbc;
    }

    Answer transfer(String key, UUID source, UUID target, String amount) throws InterruptedException {
        for (int conflicts = 0; conflicts < MAX_ATTEMPTS; conflicts++) {
            EntityExchangeResult<String> result = api.transfer(key, source, target, amount)
                    .expectBody(String.class)
                    .returnResult();
            if (result.getStatus() != HttpStatus.CONFLICT) {
                return new Answer(
                        result.getStatus().value(),
                        requireNonNull(result.getResponseBody()),
                        "true".equals(result.getResponseHeaders().getFirst(WalletApiDriver.IDEMPOTENT_REPLAYED)),
                        conflicts);
            }
            String retryAfter = requireNonNull(result.getResponseHeaders().getFirst("Retry-After"));
            Thread.sleep(Duration.ofSeconds(Long.parseLong(retryAfter)));
        }
        throw new AssertionError("still 409 after " + MAX_ATTEMPTS + " attempts with key " + key);
    }

    /** How many transfers have credited the wallet. */
    long transfersInto(UUID wallet) {
        return jdbc.sql("""
                        SELECT count(*) FROM ledger_transactions t
                        WHERE t.type = 'TRANSFER'
                          AND EXISTS (SELECT 1 FROM entries e WHERE e.transaction_id = t.id AND e.account_id = :wallet)
                        """).param("wallet", wallet).query(Long.class).single();
    }
}
