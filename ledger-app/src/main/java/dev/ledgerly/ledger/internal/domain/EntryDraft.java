package dev.ledgerly.ledger.internal.domain;

import dev.ledgerly.shared.Money;
import java.util.UUID;

/** An entry that posting rules accepted, with the account balance right after it. Not yet written. */
public record EntryDraft(UUID accountId, Money amount, Money balanceAfter) {}
