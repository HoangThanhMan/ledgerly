package dev.ledgerly.wallet;

import static dev.ledgerly.wallet.WalletApiDriver.expectProblem;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * A posting must not wait forever for a row lock. Another session holds the lock on a wallet here, the way a slow or
 * stuck transaction would.
 */
class LockTimeoutIT extends AbstractIntegrationTest {

    private static final String LOCK_WAIT_TIMER = "ledgerly.posting.lock.wait";

    private final DataSource dataSource;
    private final MeterRegistry meters;
    private final WalletApiDriver api;

    LockTimeoutIT(@Autowired RestTestClient client, @Autowired DataSource dataSource, @Autowired MeterRegistry meters) {
        this.dataSource = dataSource;
        this.meters = meters;
        this.api = new WalletApiDriver(client);
    }

    @Test
    void transferThatCannotLockAWalletInTimeIsRejectedAsOverloaded() throws Exception {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();
        Timer lockWait = meters.timer(LOCK_WAIT_TIMER);
        long waitsBefore = lockWait.count();
        double waitedBefore = lockWait.totalTime(TimeUnit.MILLISECONDS);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockRow(holder, source);
            long started = System.nanoTime();

            // On another thread with a deadline: if the lock wait were unbounded, the call would never return.
            RestTestClient.ResponseSpec response = CompletableFuture.supplyAsync(
                            () -> api.transfer(source, target, "10"))
                    .get(10, TimeUnit.SECONDS);

            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            expectProblem(response, HttpStatus.SERVICE_UNAVAILABLE, "overloaded");
            response.expectHeader().valueEquals("Retry-After", "1");
            assertThat(waited).isBetween(Duration.ofMillis(1_900), Duration.ofSeconds(5));
            holder.rollback();
        }

        assertThat(api.balanceOf(source)).isEqualTo("100");
        assertThat(api.entriesOf(target)).isEmpty();
        assertThat(lockWait.count()).isEqualTo(waitsBefore + 1);
        assertThat(lockWait.totalTime(TimeUnit.MILLISECONDS) - waitedBefore).isGreaterThanOrEqualTo(1_900);
    }

    @Test
    void lockWaitIsMeasuredForEveryPosting() {
        UUID source = api.openWalletWith(100);
        UUID target = api.openWallet();
        long waitsBefore = meters.timer(LOCK_WAIT_TIMER).count();

        api.transfer(source, target, "10").expectStatus().isCreated();

        assertThat(meters.timer(LOCK_WAIT_TIMER).count()).isEqualTo(waitsBefore + 1);
    }

    private static void lockRow(Connection connection, UUID accountId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT 1 FROM accounts WHERE id = ? FOR NO KEY UPDATE")) {
            statement.setObject(1, accountId);
            statement.execute();
        }
    }
}
