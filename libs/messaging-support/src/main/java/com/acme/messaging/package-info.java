/**
 * The one place in the codebase that knows a broker exists.
 *
 * <p>{@link com.acme.kernel.event.EventContract} states what a stream promises - the key that
 * orders it, whether history survives, how many times a consumer may see a message. This package
 * turns that statement into the configuration a broker actually understands: Kafka topic configs,
 * or RabbitMQ stream arguments. Nothing outside it constructs a physical topic name, and nothing in
 * its public API mentions a Kafka or AMQP type, so moving a stream between brokers is a change
 * here and nowhere else.
 *
 * <p>Where a broker cannot honour a declaration, the translation fails at startup rather than
 * approximating it. A stream that degrades from "the current value of every key, forever" to
 * "whatever arrived this week" is wrong in a way that surfaces months later, in a consumer, as a
 * rebuilt view that is quietly incomplete - so the boot failure is the cheaper outcome.
 *
 * <p>See {@code docs/adr/0007-broker-neutral-event-contracts.md} and
 * {@code docs/principles/P-070-event-semantics.md}.
 */
@NullMarked
package com.acme.messaging;

import org.jspecify.annotations.NullMarked;
