package dev.ledgerly.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Currency;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    private static final UUID SOURCE = UUID.fromString("0199a7c2-5d1e-7b3a-9f00-6f1c2d3e4a5b");
    private static final UUID TARGET = UUID.fromString("0199a7c2-5d1e-7b3a-9f00-6f1c2d3e4a5c");

    /** Shaped like a request DTO: components are deliberately not in alphabetical order. */
    record Transfer(UUID sourceWalletId, UUID targetWalletId, String amount, Currency currency) {}

    private final RequestHasher hasher = new RequestHasher();

    @Test
    void hashIsSha256OfMethodPathAndTheBodyWithSortedFields() throws NoSuchAlgorithmException {
        String canonical = "POST\n/v1/transfers\n"
                + "{\"amount\":\"150000\",\"currency\":\"VND\","
                + "\"sourceWalletId\":\"0199a7c2-5d1e-7b3a-9f00-6f1c2d3e4a5b\","
                + "\"targetWalletId\":\"0199a7c2-5d1e-7b3a-9f00-6f1c2d3e4a5c\"}";
        String expected = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));

        assertThat(hasher.hash("POST", "/v1/transfers", transfer("150000")))
                .isEqualTo(expected)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void equalRequestsHashTheSame() {
        assertThat(hasher.hash("POST", "/v1/transfers", transfer("150000")))
                .isEqualTo(hasher.hash("POST", "/v1/transfers", transfer("150000")));
    }

    @Test
    void orderOfMapEntriesDoesNotMatter() {
        Map<String, Object> oneWay = new LinkedHashMap<>();
        oneWay.put("walletId", SOURCE);
        oneWay.put("amount", "5");
        Map<String, Object> otherWay = new LinkedHashMap<>();
        otherWay.put("amount", "5");
        otherWay.put("walletId", SOURCE);

        assertThat(hasher.hash("POST", "/v1/admin/deposits", oneWay))
                .isEqualTo(hasher.hash("POST", "/v1/admin/deposits", otherWay));
    }

    @Test
    void anotherBodyMethodOrPathHashesDifferently() {
        String hash = hasher.hash("POST", "/v1/transfers", transfer("150000"));

        assertThat(hasher.hash("POST", "/v1/transfers", transfer("150001"))).isNotEqualTo(hash);
        assertThat(hasher.hash("PUT", "/v1/transfers", transfer("150000"))).isNotEqualTo(hash);
        assertThat(hasher.hash("POST", "/v1/admin/deposits", transfer("150000")))
                .isNotEqualTo(hash);
    }

    private static Transfer transfer(String amount) {
        return new Transfer(SOURCE, TARGET, amount, Currency.getInstance("VND"));
    }
}
