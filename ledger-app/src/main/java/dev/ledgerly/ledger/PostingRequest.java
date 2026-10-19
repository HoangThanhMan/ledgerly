package dev.ledgerly.ledger;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A ledger transaction to post. {@code reference} is the id of the related business object, if any.
 *
 * @see PostingResult
 */
public record PostingRequest(TransactionType type, @Nullable String reference, List<Posting> postings) {

    public PostingRequest {
        postings = List.copyOf(postings);
    }
}
