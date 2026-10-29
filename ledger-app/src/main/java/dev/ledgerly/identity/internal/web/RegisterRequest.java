package dev.ledgerly.identity.internal.web;

import dev.ledgerly.identity.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

@Schema(description = "A new user.")
record RegisterRequest(
        @Schema(description = "Email to sign in with. Case does not matter.", example = "an@example.com")
        @NotBlank @Email @Size(max = 254) String email,

        @Schema(description = "8 to 72 characters, and at most 72 bytes in UTF-8.", example = "correct-horse-battery")
        @NotNull @Size(min = 8, max = 72) String password,

        @Schema(description = "USER (the default) buys tickets. ORGANIZER also runs events.", example = "USER")
        @Nullable Role role) {}
