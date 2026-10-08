package dev.ledgerly.wallet.internal.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Currency;
import java.util.List;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The parts of the OpenAPI document that belong to no single endpoint. */
@Configuration(proxyBeanMethods = false)
class ApiDocumentation {

    static final String WALLETS = "Wallets";
    static final String TRANSFERS = "Transfers";
    static final String DEPOSITS = "Deposits";

    static final String IDEMPOTENCY_KEY = """
            Identifies this operation, so that it is safe to send again. Generate one value for each operation \
            (a UUID is recommended) and send that same value on every retry of it. 1 to 64 visible ASCII \
            characters. The key and the response stored under it are kept for at least 24 hours.""";

    static final String REPLAYED = """
            Present with the value `true` when this is the stored response of an earlier request with the same \
            Idempotency-Key, and nothing was done again.""";

    static final String WALLET_ID = "Id of the wallet, as returned when it was opened.";

    static {
        // A currency travels as its ISO 4217 code, not as the bean java.util.Currency looks like.
        SpringDocUtils.getConfig().replaceWithClass(Currency.class, String.class);
    }

    @Bean
    OpenAPI ledgerlyApi() {
        return new OpenAPI()
                .info(new Info().title("Ledgerly API").version("1").description("""
                        E-wallet API on a double-entry ledger: open wallets, deposit, transfer between wallets, \
                        read balances and entry history.

                        Conventions:

                        - **Amounts** are strings holding an integer in the minor unit of the currency \
                        (`"150000"` is 150,000 VND), so no client rounds them. Entry amounts are signed from the \
                        wallet's side: negative is money out, positive is money in.
                        - **Ids** of wallets and transfers are UUIDs.
                        - **Requests that move money** need an `Idempotency-Key` header. Retrying with the same \
                        key never moves money twice.
                        - **Errors** are RFC 9457 Problem Details with the media type \
                        `application/problem+json`. The `type` member tells them apart.
                        - **There is no authentication.** Whoever can reach the API can act on every wallet."""))
                // Relative, so "try it out" in Swagger UI calls whichever host served the document, and the
                // document does not change with the port or the host the application runs on.
                .servers(List.of(new Server().url("/").description("The server that serves this document")));
    }
}
