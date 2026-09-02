/**
 * Distributed cron: two mechanisms behind one rule.
 *
 * <p>Every service this template ships runs more than one instance in production. A plain
 * {@code @Scheduled} method has no idea another instance exists, so it fires on every one of
 * them, on every tick - silent double-processing until an incident makes it visible.
 * {@link com.acme.kernel.arch.InboundAdapter InboundAdapter}{@code (AdapterKind.SCHEDULER)} is
 * this repository's existing marker for "a clock tick drives a use case"; this module supplies
 * the two mechanisms that make one of those adapters actually safe to run twice at once:
 *
 * <ul>
 *   <li>{@link com.acme.scheduling.ShedLockAutoConfiguration ShedLock} - a JDBC row lock wrapping
 *       a plain {@code @Scheduled} method, for a lightweight, idempotent maintenance job.
 *   <li>{@link com.acme.scheduling.QuartzClusteringAutoConfiguration Quartz}'s clustered JDBC
 *       {@code JobStore} - persisted jobs, misfire recovery, blackout calendars - for a schedule
 *       that must survive a node dying mid-fire without waiting out a lock timeout.
 * </ul>
 *
 * <p>A service already depending on {@code temporal-support} has a third, usually better option
 * for anything that already is a workflow: Temporal's own native cron schedule
 * ({@code WorkflowOptions.setCronSchedule}), which needs neither mechanism here because
 * Temporal's server is already the single coordinator.
 *
 * <p>See {@code docs/principles/P-132-scheduled-jobs.md}.
 */
@NullMarked
package com.acme.scheduling;

import org.jspecify.annotations.NullMarked;
