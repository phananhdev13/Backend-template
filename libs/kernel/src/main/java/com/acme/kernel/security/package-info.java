/**
 * The identity of a caller, expressed once, so no layer below the edge reads a security context.
 *
 * <p>{@link com.acme.kernel.security.Actor} is the only type here: a domain rule that needs to
 * know who is asking takes one as a parameter and asks it, rather than reaching into
 * {@code SecurityContextHolder} - which behaves differently in a batch job than in a request,
 * and cannot be unit tested without standing up a security context first.
 *
 * <p>See {@code docs/principles/P-120-security-at-use-case-boundary.md}.
 */
@NullMarked
package com.acme.kernel.security;

import org.jspecify.annotations.NullMarked;
