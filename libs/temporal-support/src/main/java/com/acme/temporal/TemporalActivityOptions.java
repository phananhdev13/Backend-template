package com.acme.temporal;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/**
 * The one sanctioned way to build {@link ActivityOptions} in this platform - never
 * {@code ActivityOptions.newBuilder()} directly, which {@code WorkflowRules} in
 * {@code libs/arch-test} refuses inside a {@code @WorkflowDefinition}.
 *
 * <p>{@code ActivityOptions} builds and validates cleanly with no timeout at all - it
 * defaults to unlimited. An activity blocked forever is the RPC-with-no-deadline mistake
 * P-051 already refuses for an HTTP or gRPC client, one layer deeper: the workflow thread
 * waiting on it is now also blocked for as long as the activity is, which for a durable
 * workflow can mean indefinitely. Making the timeout a required parameter here is what
 * actually enforces the obligation {@code ActivityOptions}'s own API leaves optional.
 */
public final class TemporalActivityOptions {

    private TemporalActivityOptions() {}

    /** An activity call with this platform's default retry budget. */
    public static ActivityOptions of(Duration startToCloseTimeout) {
        return of(startToCloseTimeout, defaultRetry());
    }

    /** An activity call with a retry budget shaped for this specific activity. */
    public static ActivityOptions of(Duration startToCloseTimeout, RetryOptions retry) {
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(startToCloseTimeout)
                .setRetryOptions(retry)
                .build();
    }

    /**
     * Bounded attempts and bounded backoff growth, so a dependency outage does not turn
     * into an unbounded retry storm once it recovers - the same shape
     * {@code messaging-support}'s task-queue retry budget takes.
     */
    public static RetryOptions defaultRetry() {
        return RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaximumInterval(Duration.ofMinutes(1))
                .setMaximumAttempts(5)
                .build();
    }
}
