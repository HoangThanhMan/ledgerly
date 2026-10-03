package dev.ledgerly.ledger;

import dev.ledgerly.shared.Money;
import java.time.Instant;
import java.util.UUID;

public record AccountView(UUID id, AccountType type, Money balance, Instant createdAt) {}
