/**
 * Event semantics, stated once and honoured by every broker.
 *
 * <p>The recurring failure in event-driven systems is that the important decisions -
 * what the key is, whether history is kept or collapsed, whether a consumer may see a
 * message twice - live in broker configuration, far from the code that depends on them.
 * The topic gets recreated without compaction, or with a different key, and nothing in
 * the codebase notices until the data is wrong.
 *
 * <p>{@link com.acme.kernel.event.EventContract} moves those decisions onto the event
 * type itself. The record declares what it needs; {@code libs/messaging-support}
 * translates that into Kafka topic configuration or RabbitMQ stream arguments; and
 * structural tests reject declarations that cannot hold - a compacted stream without a
 * key, or an at-least-once consumer with no idempotency.
 *
 * <p>See {@code docs/principles/P-070-event-semantics.md} and
 * {@code docs/guides/G-030-events.md}.
 */
@NullMarked
package com.acme.kernel.event;

import org.jspecify.annotations.NullMarked;
