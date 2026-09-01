package com.acme.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TemporalActivityOptionsTest {

    @Test
    void setsTheGivenStartToCloseTimeoutAndTheDefaultRetryBudget() {
        var options = TemporalActivityOptions.of(Duration.ofSeconds(30));

        assertThat(options.getStartToCloseTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(options.getRetryOptions().getMaximumAttempts()).isEqualTo(5);
        assertThat(options.getRetryOptions().getBackoffCoefficient()).isEqualTo(2.0);
    }

    @Test
    void acceptsACustomRetryBudget() {
        var retry = io.temporal.common.RetryOptions.newBuilder()
                .setMaximumAttempts(1)
                .build();

        var options = TemporalActivityOptions.of(Duration.ofSeconds(5), retry);

        assertThat(options.getRetryOptions().getMaximumAttempts()).isEqualTo(1);
    }
}
