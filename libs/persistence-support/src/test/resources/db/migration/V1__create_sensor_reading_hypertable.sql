-- A hypertable for raw sensor readings, proving the composite-key mapping and TimescaleDB
-- addressing this module's persistence skill documents actually work against a real server, not
-- merely that the SQL parses. See docs/adr/0018-timescaledb-extension-for-time-series-data.md.
create extension if not exists timescaledb;

create table sensor_reading (
    device_id   text                     not null,
    recorded_at timestamptz              not null,
    temperature double precision,
    humidity    double precision,
    -- TimescaleDB requires every unique constraint - the primary key included - to contain the
    -- partitioning column, because uniqueness is enforced per chunk, not across the whole table.
    -- A bare surrogate id here fails at CREATE TABLE time with "cannot create a unique index
    -- without the column recorded_at (used in partitioning)" - confirmed against a real
    -- TimescaleDB 2.29.1 server while building this migration. Map it in JPA as a composite
    -- @EmbeddedId, never a generated surrogate key: see SensorReadingId.
    primary key (device_id, recorded_at)
) with (
    timescaledb.hypertable,
    timescaledb.partition_column = 'recorded_at'
);
