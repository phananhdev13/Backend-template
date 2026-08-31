/**
 * The correlation identifier that makes an incident a query instead of an excavation.
 *
 * <p>A customer says an order was charged but never confirmed. The evidence is spread over the
 * gateway access log, this service's application log, the payment adapter's client log and the
 * consumer log of whichever pod owned that partition - four systems, four clocks, and no shared
 * key. Reconstructing one request from timestamps is guesswork the moment two customers did
 * similar things in the same second. One identifier that crosses every hop turns that into a
 * single query.
 *
 * <p>{@link com.acme.observability.CorrelationIdFilter} puts the identifier in the SLF4J
 * {@link org.slf4j.MDC} at the HTTP edge, so every log line emitted while serving the request
 * carries it without any call site remembering to. Code that needs the value - an outbound
 * adapter stamping an {@code EventEnvelope}, an error response quoting it back to the client -
 * reads it through {@link com.acme.observability.Correlation}, or, where a module dependency is
 * not wanted, through the MDC key {@code "correlationId"} directly.
 *
 * <p>The MDC is thread-local and request threads are pooled, which is the one hazard worth
 * naming: an identifier that is not cleared when a request ends is inherited by the next request
 * that lands on that thread, and the resulting log is not merely unhelpful, it is wrong - it
 * attributes one customer's failure to another customer's request. The filter clears it in a
 * {@code finally} block for that reason.
 *
 * <p>See {@code docs/principles/P-060-observability.md}.
 */
@NullMarked
package com.acme.observability;

import org.jspecify.annotations.NullMarked;
