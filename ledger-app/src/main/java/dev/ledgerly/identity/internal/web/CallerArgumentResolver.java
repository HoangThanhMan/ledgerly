package dev.ledgerly.identity.internal.web;

import static java.util.Objects.requireNonNull;

import dev.ledgerly.identity.Caller;
import dev.ledgerly.identity.Role;
import dev.ledgerly.identity.internal.application.JwtIssuer;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Fills a handler parameter of type {@link Caller} from the access token the security filter has verified. */
class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(Caller.class);
    }

    @Override
    public Caller resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            // Only reachable if a handler that needs a caller is left open in SecurityConfig.
            throw new IllegalStateException(
                    parameter.getExecutable() + " needs a signed-in caller, but the request has no access token");
        }
        // Every token this application issues has both. One without them was not issued here.
        Jwt jwt = token.getToken();
        String subject = requireNonNull(jwt.getSubject(), "access token has no subject");
        String role = requireNonNull(jwt.getClaimAsString(JwtIssuer.ROLE_CLAIM), "access token has no role");
        return new Caller(UUID.fromString(subject), Role.valueOf(role));
    }
}
