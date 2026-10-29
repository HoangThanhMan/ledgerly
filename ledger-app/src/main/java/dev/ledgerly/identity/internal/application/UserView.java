package dev.ledgerly.identity.internal.application;

import dev.ledgerly.identity.Role;
import java.time.Instant;
import java.util.UUID;

public record UserView(UUID id, String email, Role role, Instant createdAt) {}
