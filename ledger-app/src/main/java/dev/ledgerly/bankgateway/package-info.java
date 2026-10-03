/**
 * HTTP client for mock-bank ({@code @HttpExchange}): timeouts, retries with backoff and jitter, results mapped to
 * a sealed type.
 *
 * <p>The public API lives at the package root. Everything under {@code internal} is private to the module.
 */
@NullMarked
package dev.ledgerly.bankgateway;

import org.jspecify.annotations.NullMarked;
