package dev.ledgerly.identity.internal.web;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An access token. Send it as `Authorization: Bearer <accessToken>`.")
record TokenResponse(
        @Schema(description = "A JWT.") String accessToken,

        @Schema(description = "Always `Bearer`.", example = "Bearer")
        String tokenType,

        @Schema(description = "Seconds until the token expires.", example = "3600")
        long expiresIn) {}
