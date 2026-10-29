package dev.ledgerly.identity;

import java.util.UUID;

/**
 * The signed-in user a request is made by, as its access token says. A request handler gets it by declaring a
 * parameter of this type.
 */
public record Caller(UUID userId, Role role) {

    public boolean isOrganizer() {
        return role == Role.ORGANIZER;
    }
}
