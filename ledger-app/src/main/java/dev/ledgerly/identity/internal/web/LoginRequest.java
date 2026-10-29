package dev.ledgerly.identity.internal.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Credentials of a registered user.")
record LoginRequest(
        @Schema(description = "Email given at sign-up. Case does not matter.", example = "an@example.com") @NotBlank String email,

        @Schema(description = "The password.", example = "correct-horse-battery") @NotNull String password) {}
