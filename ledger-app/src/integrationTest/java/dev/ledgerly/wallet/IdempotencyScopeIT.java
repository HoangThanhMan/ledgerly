package dev.ledgerly.wallet;

import static dev.ledgerly.wallet.WalletApiDriver.IDEMPOTENT_REPLAYED;
import static dev.ledgerly.wallet.WalletApiDriver.body;
import static dev.ledgerly.wallet.WalletApiDriver.rawBody;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * An Idempotency-Key is the client's name for one of its own operations. Two users who happen to pick the same key
 * name two different operations.
 */
class IdempotencyScopeIT extends AbstractIntegrationTest {

    private final WalletApiDriver alice;
    private final WalletApiDriver bob;

    IdempotencyScopeIT(@Autowired RestTestClient client) {
        this.alice = new WalletApiDriver(AuthDriver.signedIn(client, Role.USER));
        this.bob = new WalletApiDriver(AuthDriver.signedIn(client, Role.USER));
    }

    @Test
    void sameKeyFromTwoUsersRunsTwice() {
        String key = "k-" + UUID.randomUUID();
        UUID aliceWallet = alice.openWalletWith(1_000);
        UUID bobWallet = bob.openWalletWith(1_000);
        UUID payee = alice.openWallet();

        RestTestClient.ResponseSpec fromAlice = alice.transfer(key, aliceWallet, payee, "100");
        RestTestClient.ResponseSpec fromBob = bob.transfer(key, bobWallet, payee, "100");

        fromAlice.expectStatus().isCreated().expectHeader().doesNotExist(IDEMPOTENT_REPLAYED);
        fromBob.expectStatus().isCreated().expectHeader().doesNotExist(IDEMPOTENT_REPLAYED);
        assertThat(body(fromAlice).get("id")).isNotEqualTo(body(fromBob).get("id"));
        assertThat(bob.balanceOf(bobWallet)).isEqualTo("900");
    }

    @Test
    void sameKeySameUserStillReplays() {
        String key = "k-" + UUID.randomUUID();
        UUID source = alice.openWalletWith(1_000);
        UUID target = alice.openWallet();

        String first = rawBody(
                alice.transfer(key, source, target, "100").expectStatus().isCreated());
        RestTestClient.ResponseSpec retry = alice.transfer(key, source, target, "100");

        retry.expectStatus().isCreated().expectHeader().valueEquals(IDEMPOTENT_REPLAYED, "true");
        assertThat(rawBody(retry)).isEqualTo(first);
        assertThat(alice.balanceOf(source)).isEqualTo("900");
    }
}
