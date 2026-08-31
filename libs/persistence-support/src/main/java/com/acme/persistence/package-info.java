/**
 * Persistence conventions that survive contact with production.
 *
 * <p>Migrations live at {@code classpath:db/migration/<service>} and are additive: expand, migrate,
 * contract. A migration that has been applied is never edited - Flyway checksums it, and every
 * environment that already ran it will refuse to start.
 *
 * <p>See {@code docs/principles/P-110-expand-migrate-contract.md}.
 */
@NullMarked
package com.acme.persistence;

import org.jspecify.annotations.NullMarked;
