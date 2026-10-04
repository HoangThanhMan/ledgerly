package dev.ledgerly.shared.problem;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ProblemDetailsAdviceTest {

    private final RestTestClient client = RestTestClient.bindToController(new FailingController())
            .configureServer(server -> server.setControllerAdvice(new ProblemDetailsAdvice()))
            .build();

    @Test
    void problemExceptionBecomesAProblemOfItsType() {
        client.get()
                .uri("/rejected")
                .exchange()
                .expectStatus()
                .isEqualTo(422)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .json("""
                        {
                          "type": "/problems/insufficient-funds",
                          "title": "Insufficient funds",
                          "status": 422,
                          "detail": "Wallet has 1 VND, the transfer needs 2 VND",
                          "instance": "/rejected"
                        }
                        """);
    }

    @Test
    void unexpectedExceptionBecomesAnInternalErrorThatDoesNotRevealItsCause() {
        client.get()
                .uri("/broken")
                .exchange()
                .expectStatus()
                .isEqualTo(500)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .json("""
                        {
                          "type": "/problems/internal-error",
                          "title": "Internal error",
                          "status": 500,
                          "detail": "An unexpected error occurred",
                          "instance": "/broken"
                        }
                        """);
    }

    @Test
    void errorsRaisedBySpringMvcKeepTheirOwnStatusAndTitle() {
        client.post()
                .uri("/rejected")
                .exchange()
                .expectStatus()
                .isEqualTo(405)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Method Not Allowed")
                .jsonPath("$.status")
                .isEqualTo(405);
    }

    @RestController
    static class FailingController {

        @GetMapping("/rejected")
        String rejected() {
            throw new ProblemException(ProblemType.INSUFFICIENT_FUNDS, "Wallet has 1 VND, the transfer needs 2 VND");
        }

        @GetMapping("/broken")
        String broken() {
            throw new IllegalStateException("password=hunter2 leaked from the database driver");
        }
    }
}
