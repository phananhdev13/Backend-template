package com.acme.persistence.fixture;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * Deliberately does not extend {@code AuditableEntity}: a raw time-series reading is
 * insert-only, never updated after it lands, so an optimistic-lock {@code @Version} column would
 * be dead weight on every row - there is nothing to race against. {@code recordedAt} on
 * {@link SensorReadingId} already is the audit timestamp this table needs.
 */
@Entity
@Table(name = "sensor_reading")
public class SensorReadingEntity {

    @EmbeddedId
    private SensorReadingId id;

    private @Nullable Double temperature;

    private @Nullable Double humidity;

    protected SensorReadingEntity() {
        // Required by JPA.
    }

    public SensorReadingEntity(SensorReadingId id, @Nullable Double temperature, @Nullable Double humidity) {
        this.id = id;
        this.temperature = temperature;
        this.humidity = humidity;
    }

    public SensorReadingId id() {
        return id;
    }

    public @Nullable Double temperature() {
        return temperature;
    }

    public @Nullable Double humidity() {
        return humidity;
    }
}
