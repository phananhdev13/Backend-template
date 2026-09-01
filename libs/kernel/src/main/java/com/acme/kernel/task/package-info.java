/**
 * Background task queues: point-to-point, competing consumers, no replay.
 *
 * <p>An {@link com.acme.kernel.event.EventContract} broadcasts a fact to every interested
 * consumer group, for as long as its retention says. {@link com.acme.kernel.task.TaskContract}
 * is the other shape work takes here: one consumer processes a task and it is gone,
 * exactly the pattern a background job queue - "send this email," "provision this
 * resource" - actually needs, and a broadcast stream models only awkwardly.
 *
 * <p>{@code libs/messaging-support} provisions a RabbitMQ classic queue and its
 * dead-letter destination from the declaration; structural tests require every
 * {@code @TaskHandler} to be {@link com.acme.kernel.event.Idempotent}, because a task
 * queue redelivers on every failure path a broker has.
 *
 * <p>See {@code docs/principles/P-131-task-queues.md}.
 */
@NullMarked
package com.acme.kernel.task;

import org.jspecify.annotations.NullMarked;
