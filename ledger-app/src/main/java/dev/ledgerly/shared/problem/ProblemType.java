package dev.ledgerly.shared.problem;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** The problem types of the API. */
public enum ProblemType {
    VALIDATION_ERROR(
            HttpStatus.BAD_REQUEST,
            "validation-error",
            "Request is not valid",
            "The body, a parameter or a header is missing or malformed. Nothing was done, and an Idempotency-Key"
                    + " sent with the request was not used up."),
    WALLET_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "wallet-not-found",
            "Wallet not found",
            "No wallet has this id. System accounts are not wallets."),
    TRANSFER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "transfer-not-found",
            "Transfer not found",
            "No transfer has this id. The id of a deposit is not the id of a transfer."),
    IDEMPOTENCY_IN_PROGRESS(
            HttpStatus.CONFLICT,
            "idempotency-in-progress",
            "Request with this Idempotency-Key is still in progress",
            "An earlier request with the same Idempotency-Key has not finished. Wait for the time in Retry-After,"
                    + " then send the request again with the same key."),
    IDEMPOTENCY_KEY_REUSED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "idempotency-key-reused",
            "Idempotency-Key was already used with a different request",
            "The Idempotency-Key was first sent with another method, path or body. Use a new key for every"
                    + " operation."),
    INSUFFICIENT_FUNDS(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "insufficient-funds",
            "Insufficient funds",
            "The source wallet holds less than the amount. No money moved. The rejection is stored under the"
                    + " Idempotency-Key, so a new attempt needs a new key."),
    SAME_ACCOUNT_TRANSFER(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "same-account-transfer",
            "Source and target wallet are the same",
            "The source and the target of a transfer are the same wallet."),
    CURRENCY_MISMATCH(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "currency-mismatch",
            "Currency does not match the wallet",
            "The currency of the request is not the currency of a wallet it names."),
    BALANCE_LIMIT_EXCEEDED(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "balance-limit-exceeded",
            "Balance limit exceeded",
            "The amount would take a balance past the largest value the ledger can hold. No money moved."),
    UNSUPPORTED_CURRENCY(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "unsupported-currency",
            "Currency is not supported",
            "Wallets cannot be opened in this currency. Only VND is supported."),
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal-error",
            "Internal error",
            "Something unexpected failed. The cause is in the server log, not in the response. A request that"
                    + " moves money was rolled back and may be retried with the same Idempotency-Key."),
    OVERLOADED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "overloaded",
            "Service is overloaded",
            "The request could not get the locks it needs in time. Nothing was done. Wait for the time in"
                    + " Retry-After, then send the request again with the same Idempotency-Key.");

    private final HttpStatus status;
    private final URI uri;
    private final String slug;
    private final String title;
    private final String description;

    ProblemType(HttpStatus status, String slug, String title, String description) {
        this.status = status;
        this.uri = URI.create("/problems/" + slug);
        this.slug = slug;
        this.title = title;
        this.description = description;
    }

    public HttpStatus status() {
        return status;
    }

    /** The {@code type} member of the problem: a relative URI that identifies the problem type. */
    public URI uri() {
        return uri;
    }

    /** The last segment of {@link #uri()}: a short name for the problem type. */
    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    /** When the problem occurs and what a client should do about it, for the API documentation. */
    public String description() {
        return description;
    }

    /** A problem of this type. Its {@code instance} is left for whoever knows the request path. */
    public ProblemDetail toProblemDetail(@Nullable String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(uri);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }
}
