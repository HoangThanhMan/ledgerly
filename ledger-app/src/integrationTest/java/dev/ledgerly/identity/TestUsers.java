package dev.ledgerly.identity;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Users for tests that call the services directly rather than through the API, and so need an owner for their
 * wallets but no way to sign in.
 */
public final class TestUsers {

    private TestUsers() {}

    /** A user with a unique email and a password hash nothing matches. */
    public static UUID create(JdbcClient jdbc, Role role) {
        return jdbc.sql("INSERT INTO users (email, password_hash, role) VALUES (:email, 'not-a-bcrypt-hash', :role)"
                        + " RETURNING id")
                .param("email", "service-" + UUID.randomUUID() + "@test.ledgerly.dev")
                .param("role", role.name())
                .query(UUID.class)
                .single();
    }

    /** An organizer, who may deposit into any wallet, as a caller of the wallet services. */
    public static Caller organizer(JdbcClient jdbc) {
        return new Caller(create(jdbc, Role.ORGANIZER), Role.ORGANIZER);
    }
}
