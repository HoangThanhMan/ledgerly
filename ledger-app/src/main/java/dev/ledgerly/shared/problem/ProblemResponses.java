package dev.ledgerly.shared.problem;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The problems a request handler can answer with, besides the two every handler shares: {@code validation-error}
 * and {@code internal-error}. The OpenAPI document lists them as the error responses of the operation.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProblemResponses {

    ProblemType[] value() default {};
}
