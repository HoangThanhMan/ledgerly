package dev.ledgerly.identity.internal.application;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import dev.ledgerly.identity.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Issues the access tokens of the API: HS256 JWTs whose subject is the user id and whose {@code role} claim is the
 * user's role. A token cannot be revoked, so its lifetime bounds the damage of a leaked one.
 */
@Component
public class JwtIssuer {

    /** Name of the claim that holds the {@link Role}. */
    public static final String ROLE_CLAIM = "role";

    private final JwtEncoder encoder;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    JwtIssuer(
            @Value("${ledgerly.security.jwt.secret}") String secret,
            @Value("${ledgerly.security.jwt.ttl:1h}") Duration ttl) {
        this(JwtKeys.secretKey(secret), ttl, Clock.systemUTC());
    }

    JwtIssuer(SecretKey key, Duration ttl, Clock clock) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
        this.ttl = ttl;
        this.clock = clock;
    }

    public String issue(UUID userId, Role role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .claim(ROLE_CLAIM, role.name())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** How long a token stays valid after it is issued. */
    public Duration ttl() {
        return ttl;
    }
}
