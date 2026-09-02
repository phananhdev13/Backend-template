-- Every hypertable states its own retention explicitly (P-112) rather than growing forever by
-- default. Raw readings older than 90 days are dropped; sensor_reading_hourly, already
-- materialized by the time its source chunks are dropped, is unaffected - only the ability to
-- recompute a *different* rollup over that now-dropped raw data is lost.
select add_retention_policy('sensor_reading', interval '90 days');

-- Enabling the columnstore compresses chunks older than one chunk interval by default (7 days,
-- since no chunk_time_interval was stated) - a real, silently-applied policy, not merely an
-- opt-in knob. Confirmed against a real server: the ALTER TABLE below creates that default
-- policy as a side effect, so stating our own number requires removing it first -
-- add_columnstore_policy's own if_not_exists => true only warns and leaves the existing default
-- in place; it does not update it.
alter table sensor_reading set (
    timescaledb.enable_columnstore = true,
    timescaledb.segmentby          = 'device_id',
    timescaledb.orderby            = 'recorded_at desc'
);
call remove_columnstore_policy('sensor_reading', if_exists => true);
call add_columnstore_policy('sensor_reading', after => interval '30 days');
