package dev.ledgerly.notification;

import org.jspecify.annotations.Nullable;

/** A message that is not a usable event. Reading it again cannot help, so it is never retried. */
public class MalformedEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    MalformedEventException(String reason, @Nullable Throwable cause) {
        super(reason, cause);
    }
}
