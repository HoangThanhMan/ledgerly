package dev.ledgerly.ledger;

import dev.ledgerly.shared.Money;
import java.util.UUID;

/**
 * One leg of a ledger transaction: a signed amount seen from the account's side. Negative moves money out of the
 * account, positive moves money in.
 */
public record Posting(UUID accountId, Money amount) {}
