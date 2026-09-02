-- A continuous aggregate: TimescaleDB refreshes this incrementally on a schedule instead of
-- recomputing from scratch on every query - the time-series shape of P-032's "reads are shaped
-- separately from writes."
--
-- WITH NO DATA is mandatory here, not a style choice: Flyway runs every migration inside a
-- transaction, and materializing on creation (WITH DATA, the default) fails with "CREATE
-- MATERIALIZED VIEW ... WITH DATA cannot run inside a transaction block" - confirmed against a
-- real server while building this migration. The refresh policy below performs the first
-- materialization on its own schedule instead of at migration time.
create materialized view sensor_reading_hourly
with (timescaledb.continuous) as
select device_id,
       time_bucket('1 hour', recorded_at) as bucket,
       avg(temperature)                   as avg_temperature,
       avg(humidity)                      as avg_humidity,
       count(*)                           as sample_count
from sensor_reading
group by device_id, bucket
with no data;

select add_continuous_aggregate_policy('sensor_reading_hourly',
    start_offset      => interval '3 hours',
    end_offset        => interval '1 hour',
    schedule_interval  => interval '1 hour');
