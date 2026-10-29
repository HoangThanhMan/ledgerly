package dev.ledgerly.identity;

import java.util.UUID;

/** Entry point of the identity module for other modules. Signing in happens over HTTP, not through here. */
public interface IdentityApi {

    /**
     * The id of the user with this email, signing them up first if there is none. A user that exists keeps the
     * password and role they have.
     *
     * @param password at most 72 bytes in UTF-8
     */
    UUID findOrRegister(String email, String password, Role role);
}
