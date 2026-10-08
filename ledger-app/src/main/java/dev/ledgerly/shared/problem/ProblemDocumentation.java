package dev.ledgerly.shared.problem;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

/**
 * Writes the error responses of the OpenAPI document from the same {@link ProblemType} catalogue the handlers answer
 * with, so the document cannot name a problem the application does not have, or describe one differently.
 *
 * <p>Every operation gets the two problems any request can run into. The others come from the handler's
 * {@link ProblemResponses}. Problems that share a status become one response with one example each, since OpenAPI
 * allows a single response per status.
 */
@Component
class ProblemDocumentation implements OperationCustomizer, GlobalOpenApiCustomizer {

    private static final String PROBLEM_SCHEMA = "Problem";
    private static final String PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    private static final Set<ProblemType> OF_EVERY_OPERATION =
            EnumSet.of(ProblemType.VALIDATION_ERROR, ProblemType.INTERNAL_ERROR);

    /** Problems that tell the client when to try again. */
    private static final Set<ProblemType> WITH_RETRY_AFTER =
            EnumSet.of(ProblemType.IDEMPOTENCY_IN_PROGRESS, ProblemType.OVERLOADED);

    @Override
    public void customise(OpenAPI openApi) {
        openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
    }

    @Override
    public Operation customize(Operation operation, HandlerMethod handler) {
        Set<ProblemType> problems = EnumSet.copyOf(OF_EVERY_OPERATION);
        ProblemResponses declared = handler.getMethodAnnotation(ProblemResponses.class);
        if (declared != null) {
            problems.addAll(List.of(declared.value()));
        }
        Map<Integer, List<ProblemType>> byStatus = problems.stream()
                .collect(Collectors.groupingBy(type -> type.status().value(), TreeMap::new, Collectors.toList()));
        byStatus.forEach(
                (status, types) -> operation.getResponses().addApiResponse(status.toString(), response(types)));
        return operation;
    }

    private static ApiResponse response(List<ProblemType> types) {
        MediaType problemJson = new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA));
        for (ProblemType type : types) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", type.uri().toString());
            value.put("title", type.title());
            value.put("status", type.status().value());
            problemJson.addExamples(
                    type.slug(),
                    new Example()
                            .summary(type.title())
                            .description(type.description())
                            .value(value));
        }
        ApiResponse response = new ApiResponse()
                .description(types.stream().map(ProblemType::title).collect(Collectors.joining(", or: ")))
                .content(new Content().addMediaType(PROBLEM_JSON, problemJson));
        if (types.stream().anyMatch(WITH_RETRY_AFTER::contains)) {
            response.addHeaderObject(
                    "Retry-After",
                    new Header()
                            .description("Seconds to wait before sending the same request again.")
                            .schema(new IntegerSchema().example(1)));
        }
        return response;
    }

    private static Schema<?> problemSchema() {
        Schema<?> invalidInput = new ObjectSchema()
                .addProperty(
                        "field",
                        new StringSchema()
                                .description("The JSON field, query parameter or header that was rejected.")
                                .example("amount"))
                .addProperty(
                        "message",
                        new StringSchema()
                                .description("Why it was rejected.")
                                .example("must be a positive integer of minor units with at most 18 digits"));
        StringSchema type = new StringSchema();
        for (ProblemType problem : ProblemType.values()) {
            type.addEnumItem(problem.uri().toString());
        }
        type.description("Identifies the kind of problem. Stable: safe to branch on. Errors raised by the HTTP layer"
                        + " itself, such as an unknown path or a method that is not allowed, have no `type`.")
                .example(ProblemType.INSUFFICIENT_FUNDS.uri().toString());
        return new ObjectSchema()
                .description("An error, as RFC 9457 Problem Details. Every error of the API has this shape. Tell errors"
                        + " apart by `type`, never by `title` or `detail`.")
                .addProperty("type", type)
                .addProperty(
                        "title",
                        new StringSchema()
                                .description("Short name of the kind of problem, the same for every occurrence.")
                                .example(ProblemType.INSUFFICIENT_FUNDS.title()))
                .addProperty(
                        "status",
                        new IntegerSchema()
                                .description("The HTTP status of the response.")
                                .example(ProblemType.INSUFFICIENT_FUNDS.status().value()))
                .addProperty(
                        "detail",
                        new StringSchema()
                                .description("What went wrong in this occurrence, for a human to read.")
                                .example("Wallet 01a11ad9-7eb8-7b3e-9d1f-2f3c6f1c2a10 has 350000 VND,"
                                        + " the transfer needs 9999999 VND"))
                .addProperty(
                        "instance",
                        new StringSchema()
                                .description("Path of the request that failed.")
                                .example("/v1/transfers"))
                .addProperty(
                        "errors",
                        new ArraySchema()
                                .items(invalidInput)
                                .description("Only on `validation-error`, and only when the rejected inputs are known:"
                                        + " one item per rejected input."));
    }
}
