package dev.ledgerly.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import dev.ledgerly.AbstractIntegrationTest;
import dev.ledgerly.shared.problem.ProblemType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The OpenAPI document is what clients, and tools generated from it, read instead of the code. These tests keep it
 * complete, and keep the copy in the repository equal to what the application serves.
 */
class OpenApiDocumentIT extends AbstractIntegrationTest {

    /** The copy of the document kept in the repository. Tests run from the module directory. */
    private static final Path COMMITTED_DOCUMENT = Path.of("openapi.yaml");

    private static final Set<String> OPERATIONS = Set.of(
            "POST /v1/wallets",
            "GET /v1/wallets/{id}",
            "GET /v1/wallets/{id}/entries",
            "POST /v1/transfers",
            "GET /v1/transfers/{id}",
            "POST /v1/admin/deposits");

    private static final List<String> MONEY_MOVING_PATHS = List.of("/v1/transfers", "/v1/admin/deposits");

    private final RestTestClient client;

    OpenApiDocumentIT(@Autowired RestTestClient client) {
        this.client = client;
    }

    @Test
    void everyOperationAndParameterIsDescribed() {
        DocumentContext document = JsonPath.parse(get("/v3/api-docs"));
        Map<String, Map<String, Object>> paths = document.read("$.paths");

        Set<String> operations = new TreeSet<>();
        paths.forEach((path, methods) ->
                methods.keySet().forEach(method -> operations.add(method.toUpperCase(Locale.ROOT) + " " + path)));
        assertThat(operations).containsExactlyInAnyOrderElementsOf(OPERATIONS);

        for (String operation : operations) {
            String method = operation.substring(0, operation.indexOf(' ')).toLowerCase(Locale.ROOT);
            String path = operation.substring(operation.indexOf(' ') + 1);
            String base = "$.paths['" + path + "']." + method;
            assertThat(document.<String>read(base + ".summary"))
                    .as("summary of %s", operation)
                    .isNotBlank();
            assertThat(document.<String>read(base + ".description"))
                    .as("description of %s", operation)
                    .hasSizeGreaterThan(40);
            // An operation without parameters has no such member at all.
            List<Map<String, Object>> parameters = document.read(base + "[?(@.parameters)].parameters[*]");
            assertThat(parameters)
                    .as("parameters of %s", operation)
                    .allSatisfy(parameter -> assertThat((String) parameter.get("description"))
                            .as("description of parameter %s", parameter.get("name"))
                            .isNotBlank());
        }
    }

    /** Generated clients and tool definitions name their functions after these, so they are chosen, not derived. */
    @Test
    void operationsHaveStableIds() {
        DocumentContext document = JsonPath.parse(get("/v3/api-docs"));

        List<String> ids = document.read("$.paths.*.*.operationId");

        assertThat(ids)
                .containsExactlyInAnyOrder(
                        "openWallet",
                        "getWallet",
                        "listWalletEntries",
                        "createTransfer",
                        "getTransfer",
                        "createDeposit");
    }

    @Test
    void everyPropertyOfEveryBodyIsDescribed() {
        DocumentContext document = JsonPath.parse(get("/v3/api-docs"));
        Map<String, Map<String, Object>> schemas = document.read("$.components.schemas");

        assertThat(schemas)
                .containsKeys(
                        "OpenWalletRequest",
                        "WalletResponse",
                        "EntryPageResponse",
                        "EntryResponse",
                        "TransferRequest",
                        "TransferResponse",
                        "DepositRequest",
                        "DepositResponse",
                        "Problem");
        schemas.forEach((name, schema) -> {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) schema.get("properties");
            assertThat(properties).as("properties of %s", name).isNotNull().isNotEmpty();
            Objects.requireNonNull(properties)
                    .forEach((property, definition) -> assertThat((String) definition.get("description"))
                            .as("description of %s.%s", name, property)
                            .isNotBlank());
        });
    }

    @Test
    void amountsAndCurrenciesAreStrings() {
        DocumentContext document = JsonPath.parse(get("/v3/api-docs"));

        for (String schema : List.of("TransferRequest", "TransferResponse", "DepositRequest")) {
            String properties = "$.components.schemas." + schema + ".properties";
            assertThat(document.<String>read(properties + ".amount.type")).isEqualTo("string");
            assertThat(document.<String>read(properties + ".currency.type")).isEqualTo("string");
        }
        assertThat(document.<String>read("$.components.schemas.TransferRequest.properties.amount.pattern"))
                .isEqualTo("[1-9][0-9]{0,17}");
        assertThat(document.<String>read("$.components.schemas.WalletResponse.properties.balance.type"))
                .isEqualTo("string");
    }

    @Test
    void operationsThatMoveMoneyRequireAnIdempotencyKeyAndDocumentTheReplay() {
        DocumentContext document = JsonPath.parse(get("/v3/api-docs"));

        for (String path : MONEY_MOVING_PATHS) {
            String post = "$.paths['" + path + "'].post";
            List<Map<String, Object>> key =
                    document.read(post + ".parameters[?(@.name == 'Idempotency-Key' && @.in == 'header')]");
            assertThat(key)
                    .as("Idempotency-Key of POST %s", path)
                    .singleElement()
                    .satisfies(parameter -> assertThat(parameter).containsEntry("required", true));
            assertThat(document.<Map<String, Object>>read(post + ".responses['201'].headers"))
                    .as("headers of the 201 of POST %s", path)
                    .containsKey("Idempotent-Replayed");
            assertThat(document.<Map<String, Object>>read(post + ".responses['409'].headers"))
                    .containsKey("Retry-After");
            assertThat(document.<Map<String, Object>>read(post + ".responses['503'].headers"))
                    .containsKey("Retry-After");
        }
        assertThat(document.<String>read(
                        "$.paths['/v1/transfers'].post.responses['201'].content['application/json'].schema['$ref']"))
                .isEqualTo("#/components/schemas/TransferResponse");
        assertThat(document.<Map<String, Object>>read("$.paths['/v1/transfers'].post.responses['201'].headers"))
                .containsKey("Location");
    }

    @Test
    void successResponsesAreJson() {
        DocumentContext document = JsonPath.parse(get("/v3/api-docs"));

        for (String status : List.of("200", "201")) {
            List<Map<String, Object>> contents = document.read("$.paths.*.*.responses['" + status + "'].content");
            assertThat(contents)
                    .isNotEmpty()
                    .allSatisfy(content -> assertThat(content).containsOnlyKeys("application/json"));
        }
    }

    @Test
    void transferListsEveryProblemItCanAnswerWith() {
        DocumentContext document = JsonPath.parse(get("/v3/api-docs"));
        String responses = "$.paths['/v1/transfers'].post.responses";

        assertThat(problemsOf(document, responses, "400")).containsExactly("validation-error");
        assertThat(problemsOf(document, responses, "404")).containsExactly("wallet-not-found");
        assertThat(problemsOf(document, responses, "409")).containsExactly("idempotency-in-progress");
        assertThat(problemsOf(document, responses, "422"))
                .containsExactlyInAnyOrder(
                        "insufficient-funds", "same-account-transfer", "currency-mismatch", "idempotency-key-reused");
        assertThat(problemsOf(document, responses, "503")).containsExactly("overloaded");
        assertThat(document.<String>read(responses + "['422'].content['application/problem+json'].schema['$ref']"))
                .isEqualTo("#/components/schemas/Problem");
        assertThat(document.<String>read(responses
                        + "['422'].content['application/problem+json'].examples['insufficient-funds'].value.type"))
                .isEqualTo("/problems/insufficient-funds");
    }

    @Test
    void everyProblemTypeOfTheApplicationAppearsInTheDocument() {
        DocumentContext document = JsonPath.parse(get("/v3/api-docs"));

        List<String> documented =
                document.read("$.paths.*.*.responses.*.content['application/problem+json']" + ".examples.*.value.type");

        assertThat(new TreeSet<>(documented))
                .containsExactlyElementsOf(new TreeSet<>(Arrays.stream(ProblemType.values())
                        .map(type -> type.uri().toString())
                        .toList()));
    }

    @Test
    void swaggerUiIsServed() {
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
    }

    /**
     * Run with {@code -Pledgerly.openapi.update=true} after changing the API on purpose: the test then rewrites the
     * committed document instead of comparing.
     */
    @Test
    void committedDocumentIsWhatTheApplicationServes() throws IOException {
        String served = get("/v3/api-docs.yaml");

        if (Boolean.getBoolean("ledgerly.openapi.update")) {
            Files.writeString(COMMITTED_DOCUMENT, served);
        }

        assertThat(COMMITTED_DOCUMENT).exists();
        assertThat(Files.readString(COMMITTED_DOCUMENT))
                .as("openapi.yaml is out of date: rerun this test with -Pledgerly.openapi.update=true")
                .isEqualTo(served);
    }

    private static List<String> problemsOf(DocumentContext document, String responses, String status) {
        Map<String, Object> examples =
                document.read(responses + "['" + status + "'].content['application/problem+json'].examples");
        return List.copyOf(examples.keySet());
    }

    private String get(String path) {
        byte[] body = client.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        assertThat(body).isNotNull();
        return new String(body, StandardCharsets.UTF_8);
    }
}
