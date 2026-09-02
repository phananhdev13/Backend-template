package com.acme.persistence.fixture;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * The composite key TimescaleDB requires: a hypertable's primary key must contain the
 * partitioning column, so a bare surrogate id is not an option here. Mapped as {@code @EmbeddedId}
 * rather than {@code @IdClass} - one object instead of two declarations of the same two fields.
 */
@Embeddable
public class SensorReadingId implements Serializable {

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected SensorReadingId() {
        // Required by JPA.
    }

    public SensorReadingId(String deviceId, Instant recordedAt) {
        this.deviceId = deviceId;
        this.recordedAt = recordedAt;
    }

    public String deviceId() {
        return deviceId;
    }

    public Instant recordedAt() {
        return recordedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SensorReadingId that)) {
            return false;
        }
        return Objects.equals(deviceId, that.deviceId) && Objects.equals(recordedAt, that.recordedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, recordedAt);
    }
}
