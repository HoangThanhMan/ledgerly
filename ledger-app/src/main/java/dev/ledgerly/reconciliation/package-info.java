/**
 * Reconciles the ledger against bank statements: detects mismatches and resolves transactions stuck in UNKNOWN.
 *
 * <p>The public API lives at the package root. Everything under {@code internal} is private to the module.
 */
@NullMarked
package dev.ledgerly.reconciliation;

import org.jspecify.annotations.NullMarked;
