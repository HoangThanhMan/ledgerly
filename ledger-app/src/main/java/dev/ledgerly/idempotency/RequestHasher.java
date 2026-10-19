package dev.ledgerly.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fingerprint of a request, to tell a retry from a different request that reuses the key.
 *
 * <p>The body is hashed as the parsed request object, written back as JSON with its fields in alphabetical order.
 * Two requests that mean the same therefore hash the same, however the client ordered the fields or spaced the
 * text. The mapper is private to this class: a change to the JSON settings of the API must not change the hashes
 * of keys that are already stored.
 */
@Component
public class RequestHasher {

    private final JsonMapper canonical = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    /**
     * SHA-256 of the method, the path and the body, as 64 hex digits. Method and path are part of it so that a key
     * cannot be replayed against another endpoint.
     */
    public String hash(String method, String path, Object body) {
        String request = method + "\n" + path + "\n" + canonical.writeValueAsString(body);
        return HexFormat.of().formatHex(sha256().digest(request.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform must provide SHA-256", e);
        }
    }
}
