/**
 * Failures expressed in domain terms, so that no layer below the edge knows about HTTP.
 *
 * <p>A use case that throws {@code ResponseStatusException} has quietly become a web
 * controller, and stops working the moment the same use case is reached from a message
 * consumer or a scheduled job. The types here name <em>what went wrong</em>;
 * {@code libs/web-support} owns the single mapping from that to a status code and an
 * RFC 9457 problem response, and every entry point inherits it.
 *
 * <p>See {@code docs/principles/P-050-error-handling.md}.
 */
@NullMarked
package com.acme.kernel.error;

import org.jspecify.annotations.NullMarked;
