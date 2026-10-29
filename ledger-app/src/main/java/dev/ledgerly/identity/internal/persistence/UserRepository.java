package dev.ledgerly.identity.internal.persistence;

import dev.ledgerly.identity.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    /** A row of {@code users}. Emails are stored in lower case. */
    public record UserRow(UUID id, String email, String passwordHash, Role role, Instant createdAt) {}

    private static final String COLUMNS = "id, email, password_hash, role, created_at";

    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds a user. The email must already be in lower case.
     *
     * @throws org.springframework.dao.DuplicateKeyException if a user with this email exists, even one that was
     *     inserted by a concurrent request a moment ago
     */
    public UserRow insert(String email, String passwordHash, Role role) {
        return jdbc.sql("INSERT INTO users (email, password_hash, role) VALUES (:email, :hash, :role) RETURNING "
                        + COLUMNS)
                .param("email", email)
                .param("hash", passwordHash)
                .param("role", role.name())
                .query(UserRepository::row)
                .single();
    }

    /**
     * Adds a user unless one with this email exists. Unlike {@link #insert}, a conflict does not abort the surrounding
     * transaction.
     *
     * @return the new user, or empty if the email was taken
     */
    public Optional<UserRow> insertIfAbsent(String email, String passwordHash, Role role) {
        return jdbc.sql("INSERT INTO users (email, password_hash, role) VALUES (:email, :hash, :role)"
                        + " ON CONFLICT (email) DO NOTHING RETURNING " + COLUMNS)
                .param("email", email)
                .param("hash", passwordHash)
                .param("role", role.name())
                .query(UserRepository::row)
                .optional();
    }

    public Optional<UserRow> findByEmail(String email) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM users WHERE email = :email")
                .param("email", email)
                .query(UserRepository::row)
                .optional();
    }

    private static UserRow row(ResultSet rs, int rowNum) throws SQLException {
        return new UserRow(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
