package com.acme.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.persistence.fixture.SensorReadingEntity;
import com.acme.persistence.fixture.SensorReadingId;
import com.acme.persistence.fixture.SensorReadingJpaRepository;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves three things this module's migrations claim, against a real TimescaleDB server rather
 * than a plain Postgres one: the composite-key mapping {@link SensorReadingId} exists for is
 * actually how TimescaleDB requires a hypertable's primary key to look; the continuous aggregate
 * {@code V2} creates actually rolls raw readings up correctly; and the policies {@code V3} adds
 * actually register, not merely parse.
 *
 * <p>{@code timescale/timescaledb:2.29.1-pg17} is a real Postgres 17 server plus the extension -
 * {@code asCompatibleSubstituteFor("postgres")} tells Testcontainers' image-name validation that,
 * so {@code PostgreSQLContainer} accepts it unchanged. See
 * docs/adr/0018-timescaledb-extension-for-time-series-data.md for why this image, not a separate
 * time-series database.
 *
 * <p>{@code disabledWithoutDocker} keeps this honest on machines with no container runtime -
 * skipped with a reason rather than failing, and still run in CI.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// @DataJpaTest wraps each test in a transaction it rolls back at the end, run on one held
// connection. A continuous aggregate's refresh, like its background-worker refresh policy, reads
// through its own session and only ever sees committed data - so this module's own connection
// commits every write instead of leaving it visible only to itself.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class TimescaleHypertableIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> TIMESCALE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.29.1-pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private SensorReadingJpaRepository repository;

    @Autowired
    private DataSource dataSource;

    @Test
    void savesAndReloadsAReadingByItsCompositeDeviceAndTimeKey() {
        Instant recordedAt = Instant.parse("2026-08-30T10:15:00Z");
        SensorReadingId id = new SensorReadingId("device-1", recordedAt);

        repository.save(new SensorReadingEntity(id, 21.5, 46.0));

        assertThat(repository.findById(id)).isPresent().get().satisfies(reading -> {
            assertThat(reading.temperature()).isEqualTo(21.5);
            assertThat(reading.humidity()).isEqualTo(46.0);
        });
    }

    @Test
    void theContinuousAggregateRollsRawReadingsUpByHour() throws Exception {
        Instant hour = Instant.parse("2026-08-30T09:00:00Z");
        repository.save(new SensorReadingEntity(new SensorReadingId("device-2", hour), 20.0, 40.0));
        repository.save(new SensorReadingEntity(
                new SensorReadingId("device-2", hour.plus(10, ChronoUnit.MINUTES)), 22.0, 44.0));
        repository.save(new SensorReadingEntity(
                new SensorReadingId("device-2", hour.plus(20, ChronoUnit.MINUTES)), 24.0, 48.0));
        repository.flush();

        // V2's refresh policy runs on its own background schedule; refresh explicitly here so the
        // assertion does not depend on that schedule's timing.
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "call refresh_continuous_aggregate('sensor_reading_hourly', null, null)")) {
            statement.execute();
        }

        List<Double> averages = new ArrayList<>();
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "select avg_temperature, sample_count from sensor_reading_hourly where device_id = ?")) {
            statement.setString(1, "device-2");
            ResultSet rows = statement.executeQuery();
            assertThat(rows.next()).isTrue();
            averages.add(rows.getDouble("avg_temperature"));
            assertThat(rows.getInt("sample_count")).isEqualTo(3);
        }
        assertThat(averages).singleElement().isEqualTo(22.0);
    }

    @Test
    void theRetentionAndColumnstorePoliciesThisModuleAddedAreActuallyRegistered() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        select application_name, config
                          from timescaledb_information.jobs
                         where hypertable_name = 'sensor_reading'
                         order by application_name
                        """)) {
            ResultSet rows = statement.executeQuery();
            List<String> jobs = new ArrayList<>();
            while (rows.next()) {
                jobs.add(rows.getString("application_name") + " " + rows.getString("config"));
            }
            assertThat(jobs)
                    .anySatisfy(
                            job -> assertThat(job).contains("Retention Policy").contains("90 days"))
                    .anySatisfy(job ->
                            assertThat(job).contains("Columnstore Policy").contains("30 days"));
        }
    }
}
