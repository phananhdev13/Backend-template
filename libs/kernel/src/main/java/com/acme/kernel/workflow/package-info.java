/**
 * Durable workflow orchestration: long-running, replay-safe, surviving a worker restart.
 *
 * <p>A {@link com.acme.kernel.workflow.WorkflowDefinition} is one step further from a
 * {@link com.acme.kernel.arch.UseCase} than a use case is from the domain - orchestration
 * across use cases and aggregates rather than within one, bought at the cost of
 * determinism Temporal's replay model requires. It calls out to the world only through an
 * activity, invoked by {@code libs/temporal-support}'s worker bootstrap the same way a
 * {@code @KafkaListener} method is invoked by a broker client, never directly.
 *
 * <p>See {@code docs/principles/P-033-workflow-definitions-are-deterministic.md}.
 */
@NullMarked
package com.acme.kernel.workflow;

import org.jspecify.annotations.NullMarked;
