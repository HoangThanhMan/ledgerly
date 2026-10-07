package dev.ledgerly.shared.problem;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error of the API as Problem Details (RFC 9457).
 *
 * <p>Business rejections arrive as {@link ProblemException}. Spring MVC's own exceptions are handled by the base
 * class. Those that mean the request is malformed (status 400) get the {@code validation-error} type, with the
 * offending fields listed under {@code errors} when they are known. A lock that could not be acquired becomes a 503
 * the client may retry. Anything unexpected becomes a 500 that reveals nothing about the cause.
 *
 * <p>The answers of the endpoints that run under an {@code Idempotency-Key} do not pass through here when they are
 * stored with the key: those are rendered where the key is completed.
 */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    /** One rejected input: the JSON field, query parameter or header, and why it was rejected. */
    record InvalidInput(String field, String message) {}

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<ProblemDetail> handleProblem(ProblemException exception) {
        ProblemDetail problem = exception.type().toProblemDetail(exception.getMessage());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
        Duration retryAfter = exception.retryAfter();
        if (retryAfter != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter.toSeconds()));
        }
        return response.body(problem);
    }

    /**
     * The database gave up waiting for a row lock, or picked the request as the victim of a deadlock. Its transaction
     * was rolled back, so nothing happened and the client may simply retry.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockFailure(PessimisticLockingFailureException exception) {
        log.warn("Lock not acquired: {}", exception.getMessage());
        ProblemDetail problem = ProblemType.OVERLOADED.toProblemDetail(
                "The request could not get the locks it needs in time. Retry it.");
        return ResponseEntity.status(problem.getStatus())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return ProblemType.INTERNAL_ERROR.toProblemDetail("An unexpected error occurred");
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        exception.getBody().setProperty("errors", fieldErrors(exception.getBindingResult()));
        return super.handleMethodArgumentNotValid(exception, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<InvalidInput> errors = new ArrayList<>();
        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                errors.addAll(fieldErrors(bodyErrors));
            } else {
                String name = inputName(result.getMethodParameter());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(new InvalidInput(name, message(error)));
                }
            }
        }
        exception.getBody().setProperty("errors", errors);
        return super.handleHandlerMethodValidationException(exception, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(exception, body, headers, status, request);
        if (response != null
                && response.getBody() instanceof ProblemDetail problem
                && status.isSameCodeAs(ProblemType.VALIDATION_ERROR.status())) {
            problem.setType(ProblemType.VALIDATION_ERROR.uri());
            problem.setTitle(ProblemType.VALIDATION_ERROR.title());
        }
        return response;
    }

    private static List<InvalidInput> fieldErrors(Errors errors) {
        return errors.getFieldErrors().stream()
                .map((FieldError error) -> new InvalidInput(error.getField(), message(error)))
                .toList();
    }

    /** The name a client knows a controller parameter by: the header name for headers, else the parameter name. */
    private static String inputName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        String name = parameter.getParameterName();
        return name == null ? "arg" + parameter.getParameterIndex() : name;
    }

    private static String message(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message == null ? "is not valid" : message;
    }
}
