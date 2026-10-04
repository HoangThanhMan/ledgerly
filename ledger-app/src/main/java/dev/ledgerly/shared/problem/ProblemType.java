package dev.ledgerly.shared.problem;

import java.net.URI;
import org.springframework.http.HttpStatus;

/** The problem types of the API, as listed in docs/01-kien-truc.md §8.3. */
public enum ProblemType {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "validation-error", "Request is not valid"),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "wallet-not-found", "Wallet not found"),
    TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "transfer-not-found", "Transfer not found"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds", "Insufficient funds"),
    SAME_ACCOUNT_TRANSFER(
            HttpStatus.UNPROCESSABLE_CONTENT, "same-account-transfer", "Source and target wallet are the same"),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "currency-mismatch", "Currency does not match the wallet"),
    UNSUPPORTED_CURRENCY(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported-currency", "Currency is not supported"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error"),
    OVERLOADED(HttpStatus.SERVICE_UNAVAILABLE, "overloaded", "Service is overloaded");

    private final HttpStatus status;
    private final URI uri;
    private final String title;

    ProblemType(HttpStatus status, String slug, String title) {
        this.status = status;
        this.uri = URI.create("/problems/" + slug);
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    /** The {@code type} member of the problem: a relative URI that identifies the problem type. */
    public URI uri() {
        return uri;
    }

    public String title() {
        return title;
    }
}
