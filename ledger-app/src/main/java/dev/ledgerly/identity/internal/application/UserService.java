package dev.ledgerly.identity.internal.application;

import dev.ledgerly.identity.Role;
import dev.ledgerly.identity.internal.persistence.UserRepository;
import dev.ledgerly.identity.internal.persistence.UserRepository.UserRow;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Signs users up and in. Emails are compared in lower case, so {@code An@Example.com} and {@code an@example.com} are
 * the same user. Passwords are stored as BCrypt hashes.
 */
@Service
public class UserService {

    private final UserRepository users;
    private final JwtIssuer tokens;
    private final PasswordEncoder passwords;

    /** Compared against when the email is unknown, so that a sign-in takes as long as with a wrong password. */
    private final String unknownUserHash;

    UserService(
            UserRepository users,
            JwtIssuer tokens,
            @Value("${ledgerly.security.password.bcrypt-strength:10}") int bcryptStrength) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = new BCryptPasswordEncoder(bcryptStrength);
        this.unknownUserHash = passwords.encode("no user has this password");
    }

    /**
     * Creates a user, unless one with the same email exists.
     *
     * @param password at most 72 bytes in UTF-8: BCrypt ignores the rest
     */
    public RegisterResult register(String email, String password, Role role) {
        String normalized = normalize(email);
        try {
            UserRow user = users.insert(normalized, passwords.encode(password), role);
            return new RegisterResult.Registered(view(user));
        } catch (DuplicateKeyException e) {
            // Also the outcome when a concurrent sign-up with the same email committed first.
            return new RegisterResult.EmailTaken(normalized);
        }
    }

    public LoginResult login(String email, String password) {
        Optional<UserRow> user = users.findByEmail(normalize(email));
        String hash = user.map(UserRow::passwordHash).orElse(unknownUserHash);
        boolean matches = passwords.matches(password, hash);
        if (user.isEmpty() || !matches) {
            return new LoginResult.InvalidCredentials();
        }
        return new LoginResult.LoggedIn(tokens.issue(user.get().id(), user.get().role()), tokens.ttl());
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static UserView view(UserRow user) {
        return new UserView(user.id(), user.email(), user.role(), user.createdAt());
    }
}
