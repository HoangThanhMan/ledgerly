package dev.ledgerly.identity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerly.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.client.RestTestClient;

/** An access token in a log is a credential anyone who reads the log can use until it expires. */
@ExtendWith(OutputCaptureExtension.class)
class TokenNotLoggedIT extends AbstractIntegrationTest {

    private final RestTestClient client;

    TokenNotLoggedIT(@Autowired RestTestClient client) {
        this.client = client;
    }

    @Test
    void neitherAcceptedNorRejectedTokensAreLogged(CapturedOutput output) {
        String token = AuthDriver.register(client, Role.USER);
        String tampered = SecurityIT.tamper(token);

        for (String sent : new String[] {token, tampered}) {
            AuthDriver.withToken(client, sent)
                    .get()
                    .uri("/v1/wallets/{id}", UUID.randomUUID())
                    .exchange();
        }

        assertThat(output.getAll()).doesNotContain(token).doesNotContain(tampered);
    }
}
