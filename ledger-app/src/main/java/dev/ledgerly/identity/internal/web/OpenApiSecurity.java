package dev.ledgerly.identity.internal.web;

import dev.ledgerly.identity.Caller;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.stereotype.Component;

/**
 * Tells the OpenAPI document which operations need an access token, so that generated clients send it and Swagger
 * UI offers to. Everything but signing up and in does.
 */
@Component
class OpenApiSecurity implements GlobalOpenApiCustomizer {

    private static final String BEARER = "bearerAuth";
    private static final String OPEN_PATHS = "/v1/auth/";

    static {
        // The caller comes from the access token, not from the request: it is no parameter of the operation.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Caller.class);
    }

    @Override
    public void customise(OpenAPI openApi) {
        openApi.getComponents()
                .addSecuritySchemes(
                        BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from /v1/auth/login."));
        openApi.getPaths().forEach((path, item) -> {
            if (!path.startsWith(OPEN_PATHS)) {
                item.readOperations()
                        .forEach(operation -> operation.addSecurityItem(new SecurityRequirement().addList(BEARER)));
            }
        });
    }
}
