package dev.ledgerly.identity.internal.web;

import dev.ledgerly.identity.Role;
import dev.ledgerly.identity.internal.application.UserView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A user. The password is never returned.")
record UserResponse(
        @Schema(description = "Id of the user. Access tokens carry it as their subject.")
        UUID id,

        @Schema(description = "In lower case.") String email,
        @Schema(description = "What the user may do.") Role role,
        @Schema(description = "When the user signed up.") Instant createdAt) {

    static UserResponse from(UserView user) {
        return new UserResponse(user.id(), user.email(), user.role(), user.createdAt());
    }
}
