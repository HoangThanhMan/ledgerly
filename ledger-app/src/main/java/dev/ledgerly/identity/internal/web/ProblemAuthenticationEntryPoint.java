package dev.ledgerly.identity.internal.web;

import dev.ledgerly.shared.problem.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a request without a valid access token with a 401 in the same Problem Details shape as every other error.
 *
 * <p>The {@code WWW-Authenticate} header is the plain RFC 6750 one: {@code Bearer}, plus {@code error="invalid_token"}
 * when a token was sent but rejected. Spring Security's own entry point would add why the token was rejected and a
 * link to protected resource metadata this application does not serve.
 */
@Component
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper json;

    ProblemAuthenticationEntryPoint(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(
                HttpHeaders.WWW_AUTHENTICATE,
                exception instanceof InvalidBearerTokenException ? "Bearer error=\"invalid_token\"" : "Bearer");
        ProblemDetail problem = ProblemType.UNAUTHENTICATED.toProblemDetail(
                "Send a valid access token as Authorization: Bearer <token>. Get one from /v1/auth/login.");
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
