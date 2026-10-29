package dev.ledgerly.identity;

import static dev.ledgerly.wallet.WalletApiDriver.expectProblem;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.identity.internal.application.JwtKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.client.RestTestClient;

class SecurityIT extends AbstractIntegrationTest {

    private final RestTestClient client;
    private final String secret;

    SecurityIT(@Autowired RestTestClient client, @Value("${ledgerly.security.jwt.secret}") String secret) {
        this.client = client;
        this.secret = secret;
    }

    @Test
    void missingTokenIs401Problem() {
        expectProblem(readSomeWallet(client), HttpStatus.UNAUTHORIZED, "unauthenticated");
        readSomeWallet(client).expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @Test
    void validTokenGetsThrough() {
        RestTestClient signedIn = AuthDriver.signedIn(client, Role.USER);

        expectProblem(readSomeWallet(signedIn), HttpStatus.NOT_FOUND, "wallet-not-found");
    }

    /** Expired an hour ago: well past the minute of clock skew the decoder tolerates. */
    @Test
    void expiredTokenIs401() {
        Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
        String expired = sign(issuedAt, issuedAt.plus(Duration.ofHours(1)));

        expectProblem(
                readSomeWallet(AuthDriver.withToken(client, expired)), HttpStatus.UNAUTHORIZED, "unauthenticated");
    }

    @Test
    void tamperedTokenIs401() {
        String token = AuthDriver.register(client, Role.USER);
        RestTestClient tampered = AuthDriver.withToken(client, tamper(token));

        expectProblem(readSomeWallet(tampered), HttpStatus.UNAUTHORIZED, "unauthenticated");
        readSomeWallet(tampered)
                .expectHeader()
                .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
    }

    @Test
    void unsignedTokenIs401() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String header = base64.encodeToString("{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String claims = base64.encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\",\"role\":\"ORGANIZER\",\"exp\":"
                        + Instant.now().plusSeconds(3600).getEpochSecond() + "}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        expectProblem(
                readSomeWallet(AuthDriver.withToken(client, header + "." + claims + ".")),
                HttpStatus.UNAUTHORIZED,
                "unauthenticated");
    }

    @Test
    void signingInNeedsNoToken() {
        expectProblem(
                client.post()
                        .uri("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("email", "nobody@example.com", "password", "whatever-it-is"))
                        .exchange(),
                HttpStatus.UNAUTHORIZED,
                "invalid-credentials");
    }

    @Test
    void healthAndApiDocumentNeedNoToken() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
        client.get().uri("/v3/api-docs.yaml").exchange().expectStatus().isOk();
    }

    private static RestTestClient.ResponseSpec readSomeWallet(RestTestClient client) {
        return client.get().uri("/v1/wallets/{id}", UUID.randomUUID()).exchange();
    }

    private String sign(Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", "ORGANIZER")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(JwtKeys.secretKey(secret)))
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    /** Changes one character in the middle of the signature, where every bit of it counts. */
    static String tamper(String token) {
        int signatureStart = token.lastIndexOf('.') + 1;
        int at = signatureStart + (token.length() - signatureStart) / 2;
        char replacement = token.charAt(at) == 'A' ? 'B' : 'A';
        return token.substring(0, at) + replacement + token.substring(at + 1);
    }
}
