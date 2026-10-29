package dev.ledgerly.identity;

import dev.ledgerly.AbstractIntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** The browser pages and what they load are served to anyone, signed in or not: the pages sign in themselves. */
class StaticPagesIT extends AbstractIntegrationTest {

    private final RestTestClient anonymous;

    StaticPagesIT(@Autowired RestTestClient client) {
        this.anonymous = client;
    }

    @ParameterizedTest
    @CsvSource({
        "/, text/html",
        "/login.html, text/html",
        "/register.html, text/html",
        "/css/app.css, text/css",
        "/js/api.js, text/javascript",
        "/vendor/stomp.umd.min.js, text/javascript",
        "/fonts/be-vietnam-pro-latin-400.woff2, font/woff2"
    })
    void servedWithoutSigningIn(String path, String contentType) {
        anonymous
                .get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.parseMediaType(contentType));
    }
}
