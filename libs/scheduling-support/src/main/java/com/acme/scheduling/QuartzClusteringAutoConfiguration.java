package com.acme.scheduling;

import org.quartz.Scheduler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.PropertySource;

/**
 * Defaults that turn Boot's own {@code QuartzAutoConfiguration} from an in-memory, single-node
 * scheduler into a clustered one, without a service needing to know which fifteen
 * {@code org.quartz.*} raw properties that requires.
 *
 * <p>Wires nothing itself - {@code spring-boot-starter-quartz}'s own autoconfiguration reads
 * {@code spring.quartz.*} at bean-creation time and builds the {@code Scheduler}; this class
 * only supplies the property values a clustered JDBC {@code JobStore} needs, at the lowest
 * precedence a {@code @PropertySource} carries, so a service overrides any of them in its own
 * {@code application.yml} exactly as freely as if this class did not exist.
 *
 * <p>The QRTZ_* schema itself ships as
 * {@code db/migration/scheduling-quartz/V003__quartz_tables.sql}, reserving version 3 in a
 * consuming service's combined Flyway sequence (version 2 is reserved for
 * {@link ShedLockAutoConfiguration}'s own table, version 1 for {@code messaging-support}'s) - a
 * service that wants Quartz adds that classpath location to {@code spring.flyway.locations}
 * alongside its own migrations, the same way it already does for
 * {@code messaging-support}'s {@code processed_message} table. See
 * docs/principles/P-132-scheduled-jobs.md.
 */
@AutoConfiguration
@ConditionalOnClass(Scheduler.class)
@PropertySource("classpath:com/acme/scheduling/quartz-clustered-defaults.properties")
public class QuartzClusteringAutoConfiguration {}
