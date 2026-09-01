/**
 * Cache semantics, stated once and honoured by whichever backend serves them.
 *
 * <p>{@link com.acme.kernel.cache.CacheContract} sits beside Spring's own
 * {@code @Cacheable} family and answers the questions the framework annotation does not:
 * where entries live, how long they may be served, and whether they carry data that
 * should never be shared across instances. {@code libs/caching-support} reads the
 * declaration to build a {@code CacheManager} per {@link com.acme.kernel.cache.CacheBackend};
 * structural tests reject a contract with no matching Spring annotation, and a distributed
 * cache of personal data with no recorded decision.
 *
 * <p>See {@code docs/principles/P-130-caching-contracts.md}.
 */
@NullMarked
package com.acme.kernel.cache;

import org.jspecify.annotations.NullMarked;
