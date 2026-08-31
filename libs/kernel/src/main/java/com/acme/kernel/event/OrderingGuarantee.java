package com.acme.kernel.event;

/** The order in which a consumer is promised to observe messages. */
public enum OrderingGuarantee {

    /** No promise. Consumers must be commutative. */
    NONE,

    /**
     * Messages sharing a partition key arrive in publication order.
     *
     * <p>What almost every domain actually needs: events about one order, in order.
     * It is also the cheapest, because it allows the stream to scale by key. It holds
     * only while the key is stable and the producer is not writing the same key from two
     * places, which is why the key is declared on the contract rather than chosen at the
     * call site.
     */
    PER_KEY,

    /**
     * Every message in the stream arrives in publication order.
     *
     * <p>Requires a single partition, so the stream cannot scale beyond one consumer.
     * Almost always a modelling mistake standing in for {@link #PER_KEY}.
     */
    GLOBAL
}
