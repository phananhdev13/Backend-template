package com.acme.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Optimistic locking and audit timestamps for every persisted row.
 *
 * <p><b>Why {@code @Version} is not optional.</b> Without it, two requests that read the same row
 * and both write produce a lost update: the second overwrites the first, no exception is raised,
 * no log line is written, and no test catches it because both writes succeeded. With it, the
 * second gets an {@code OptimisticLockingFailureException}, which the edge maps to 409 and the
 * caller can retry against current state. If a test fails because of this field, the test has
 * found a real concurrency bug.
 *
 * <p>Timestamps are set by JPA lifecycle callbacks rather than by Spring Data auditing, so an
 * entity behaves identically in a plain unit test with no Spring context.
 */
@MappedSuperclass
public abstract class AuditableEntity {

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private @Nullable Instant updatedAt;

    /** The row's optimistic-lock version. */
    public long version() {
        return version;
    }

    /** When the row was first written. */
    public @Nullable Instant createdAt() {
        return createdAt;
    }

    /** When the row was last written. */
    public @Nullable Instant updatedAt() {
        return updatedAt;
    }

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
