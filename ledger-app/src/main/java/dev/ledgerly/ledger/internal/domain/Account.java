package dev.ledgerly.ledger.internal.domain;

import dev.ledgerly.shared.Money;
import java.util.UUID;

/** The state of an account that posting rules need: its current balance and whether it may go negative. */
public record Account(UUID id, Money balance, boolean allowNegative) {}
