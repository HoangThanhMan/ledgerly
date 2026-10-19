package dev.ledgerly.ledger.internal.domain;

import dev.ledgerly.ledger.PostingResult;
import java.util.List;

/** What {@link PostingRules} decided: the entries to write, or why the transaction cannot be posted. */
public sealed interface PostingDecision {

    record Accepted(List<EntryDraft> entries) implements PostingDecision {
        public Accepted {
            entries = List.copyOf(entries);
        }
    }

    record Rejected(PostingResult.Rejected reason) implements PostingDecision {}
}
