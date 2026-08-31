/**
 * The HTTP edge, in one place.
 *
 * <p>Exactly one class in a service turns a failure into a status code. That is what allows a use
 * case to be reached from a controller, a message listener and a scheduled job and behave the same
 * in all three, and it is why nothing below this package imports {@code org.springframework.http}.
 *
 * <p>See {@code docs/principles/P-050-error-handling.md} and
 * {@code docs/principles/P-080-api-versioning.md}.
 */
@NullMarked
package com.acme.web;

import org.jspecify.annotations.NullMarked;
