package dev.ledgerly.shared.problem;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
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
    void lockThatCouldNotBeAcquiredBecomesOverloadedWithARetryHint() {
        client.get()
                .uri("/contended")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("Retry-After", "1")
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .json("""
                        {
                          "type": "/problems/overloaded",
                          "title": "Service is overloaded",
                          "status": 503,
                          "detail": "The request could not get the locks it needs in time. Retry it.",
                          "instance": "/contended"
                        }
                        """);
    }

    @Test
    void problemWithARetryHintCarriesRetryAfter() {
        client.get()
                .uri("/busy")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectHeader()
                .valueEquals("Retry-After", "3")
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .json("""
                        {
                          "type": "/problems/idempotency-in-progress",
                          "title": "Request with this Idempotency-Key is still in progress",
                          "status": 409,
                          "detail": "Retry later",
                          "instance": "/busy"
                        }
                        """);
    }

    @Test
    void problemWithoutARetryHintHasNoRetryAfter() {
        client.get().uri("/rejected").exchange().expectHeader().doesNotExist("Retry-After");
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

        @GetMapping("/busy")
        String busy() {
            throw new ProblemException(ProblemType.IDEMPOTENCY_IN_PROGRESS, "Retry later", Duration.ofSeconds(3));
        }

        @GetMapping("/contended")
        String contended() {
            throw new CannotAcquireLockException("canceling statement due to lock timeout on accounts");
        }

        @GetMapping("/broken")
        String broken() {
            throw new IllegalStateException("password=hunter2 leaked from the database driver");
        }
    }
}
