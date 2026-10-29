package dev.ledgerly.wallet;

import static dev.ledgerly.wallet.WalletApiDriver.body;
import static dev.ledgerly.wallet.WalletApiDriver.expectProblem;
import static dev.ledgerly.wallet.WalletApiDriver.items;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/** A wallet belongs to the user who opened it. To anyone else it looks exactly like a wallet that does not exist. */
class WalletOwnershipIT extends AbstractIntegrationTest {

    private final RestTestClient alice;
    private final RestTestClient bob;
    private final WalletApiDriver aliceApi;
    private final WalletApiDriver bobApi;
    private final RestTestClient anonymous;

    WalletOwnershipIT(@Autowired RestTestClient client) {
        this.anonymous = client;
        this.alice = AuthDriver.signedIn(client, Role.USER);
        this.bob = AuthDriver.signedIn(client, Role.USER);
        this.aliceApi = new WalletApiDriver(alice);
        this.bobApi = new WalletApiDriver(bob);
    }

    @Test
    void listShowsOnlyMyWallets() {
        UUID first = aliceApi.openWallet();
        UUID second = aliceApi.openWallet();
        bobApi.openWallet();

        Map<String, Object> page =
                body(alice.get().uri("/v1/wallets").exchange().expectStatus().isOk());

        assertThat(items(page))
                .extracting(wallet -> wallet.get("id"))
                .containsExactly(first.toString(), second.toString());
    }

    @Test
    void othersWalletIs404LikeAMissingOne() {
        UUID aliceWallet = aliceApi.openWallet();

        Map<String, Object> others =
                problemOf(bob.get().uri("/v1/wallets/{id}", aliceWallet).exchange());
        Map<String, Object> missing =
                problemOf(bob.get().uri("/v1/wallets/{id}", UUID.randomUUID()).exchange());
        problemOf(bob.get().uri("/v1/wallets/{id}/entries", aliceWallet).exchange());

        assertThat(others).containsOnlyKeys(missing.keySet());
        assertThat(others.get("type")).isEqualTo(missing.get("type"));
        assertThat(others.get("title")).isEqualTo(missing.get("title"));
    }

    @Test
    void cannotSpendFromOthersWallet() {
        UUID aliceWallet = aliceApi.openWalletWith(1_000);
        UUID bobWallet = bobApi.openWallet();

        expectProblem(bobApi.transfer(aliceWallet, bobWallet, "100"), HttpStatus.NOT_FOUND, "wallet-not-found");

        assertThat(aliceApi.balanceOf(aliceWallet)).isEqualTo("1000");
    }

    @Test
    void canPayIntoOthersWallet() {
        UUID aliceWallet = aliceApi.openWalletWith(1_000);
        UUID bobWallet = bobApi.openWallet();

        aliceApi.transfer(aliceWallet, bobWallet, "100").expectStatus().isCreated();

        assertThat(bobApi.balanceOf(bobWallet)).isEqualTo("100");
    }

    @Test
    void transferIsVisibleToBothSidesOnly() {
        UUID aliceWallet = aliceApi.openWalletWith(1_000);
        UUID bobWallet = bobApi.openWallet();
        String transferId = (String) body(aliceApi.transfer(aliceWallet, bobWallet, "100")
                        .expectStatus()
                        .isCreated())
                .get("id");
        RestTestClient carol = AuthDriver.signedIn(anonymous, Role.USER);

        alice.get()
                .uri("/v1/transfers/{id}", transferId)
                .exchange()
                .expectStatus()
                .isOk();
        bob.get()
                .uri("/v1/transfers/{id}", transferId)
                .exchange()
                .expectStatus()
                .isOk();
        expectProblem(
                carol.get().uri("/v1/transfers/{id}", transferId).exchange(),
                HttpStatus.NOT_FOUND,
                "transfer-not-found");
    }

    @Test
    void userDepositsOnlyIntoOwnWallet() {
        UUID aliceWallet = aliceApi.openWallet();
        UUID bobWallet = bobApi.openWallet();
        WalletApiDriver organizer = new WalletApiDriver(AuthDriver.signedIn(anonymous, Role.ORGANIZER));

        aliceApi.deposit(aliceWallet, "500", "VND").expectStatus().isCreated();
        expectProblem(aliceApi.deposit(bobWallet, "500", "VND"), HttpStatus.NOT_FOUND, "wallet-not-found");
        organizer.deposit(bobWallet, "500", "VND").expectStatus().isCreated();

        assertThat(bobApi.balanceOf(bobWallet)).isEqualTo("500");
    }

    private static Map<String, Object> problemOf(RestTestClient.ResponseSpec response) {
        return body(response.expectStatus().isNotFound());
    }
}
