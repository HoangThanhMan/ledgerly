package dev.ledgerly.identity.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.ledgerly.identity.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class JwtIssuerTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(new byte[32]);

    private final Instant now = Instant.now();
    private final SecretKey key = JwtKeys.secretKey(SECRET);
    private final JwtIssuer issuer = new JwtIssuer(key, Duration.ofHours(1), Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void tokenCarriesTheUserAndTheRole() {
        UUID userId = UUID.randomUUID();

        Jwt jwt = decode(issuer.issue(userId, Role.ORGANIZER));

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ORGANIZER");
    }

    @Test
    void tokenExpiresAfterTheTtl() {
        Jwt jwt = decode(issuer.issue(UUID.randomUUID(), Role.USER));

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void keyShorterThan32BytesIsRejected() {
        String shortSecret = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> JwtKeys.secretKey(shortSecret))
                .withMessageContaining("32 bytes");
    }

    private Jwt decode(String token) {
        return NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build()
                .decode(token);
    }
}
