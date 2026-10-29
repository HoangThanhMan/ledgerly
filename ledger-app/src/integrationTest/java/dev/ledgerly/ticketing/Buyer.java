package dev.ledgerly.ticketing;

import dev.ledgerly.identity.AuthDriver;
import dev.ledgerly.identity.Role;
import dev.ledgerly.wallet.WalletApiDriver;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.test.web.servlet.client.RestTestClient;

/** A signed-in user with one wallet, ready to buy tickets. */
record Buyer(TicketingApiDriver api, WalletApiDriver wallets, UUID wallet) {

    /** Signs up {@code count} buyers in parallel, each with a wallet holding {@code balance}. */
    static List<Buyer> signUp(RestTestClient client, int count, long balance) throws Exception {
        Callable<Buyer> signUp = () -> {
            RestTestClient signedIn = AuthDriver.signedIn(client, Role.USER);
            WalletApiDriver wallets = new WalletApiDriver(signedIn);
            UUID wallet = balance > 0 ? wallets.openWalletWith(balance) : wallets.openWallet();
            return new Buyer(new TicketingApiDriver(signedIn), wallets, wallet);
        };
        return Together.run(Collections.nCopies(count, signUp), Duration.ofMinutes(2));
    }
}
