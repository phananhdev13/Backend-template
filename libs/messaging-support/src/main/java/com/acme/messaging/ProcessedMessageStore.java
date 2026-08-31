package com.acme.messaging;

import java.time.Duration;

/**
 * The ledger of deliveries already handled, which is what makes {@code @Idempotent} true.
 *
 * <p>At-least-once delivery is not a broker defect to be worked around; it is the only guarantee a
 * distributed system keeps cheaply. The cost is paid here: a handler asks whether it has seen this
 * delivery before, and acts only if it has not.
 *
 * <p>The scope is deliberately per consumer group. Two groups each get every message and each has
 * to act on it, so a ledger keyed on the message alone would suppress the second group's work and
 * look, from the outside, exactly like a lost message.
 */
public interface ProcessedMessageStore {

    /**
     * Records a delivery as handled, and reports whether this is the first time.
     *
     * <p>Must be atomic against concurrent callers: two consumers in the same group rebalancing
     * onto the same partition will call this with the same arguments at the same moment, and
     * exactly one of them has to be told it may proceed. That is why implementations insert and
     * interpret the duplicate-key failure rather than reading first and writing after - a
     * check-then-act pair has a window, and the window is where the double charge happens.
     *
     * <p>Callers should mark inside the same transaction as the work they are guarding. Marking
     * first and then failing leaves a delivery recorded but not applied, and the retry will be
     * refused.
     *
     * @param consumerGroup the group whose progress this records
     * @param messageKey the stable identifier of a logical delivery, from {@code @Idempotent.key}
     * @param retention how long the record is kept; a storage bound, not a correctness argument -
     *     a replay older than this will be handled again
     * @return true if the caller should do the work, false if it has already been done
     */
    boolean markProcessed(String consumerGroup, String messageKey, Duration retention);

    /**
     * Deletes records past their retention.
     *
     * <p>Separated from {@link #markProcessed} so that the cost of housekeeping never lands on a
     * message handler's latency, and so the schedule is the deployment's decision rather than a
     * property of message volume.
     */
    void purgeExpired();
}
