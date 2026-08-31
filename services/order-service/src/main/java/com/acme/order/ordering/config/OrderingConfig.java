package com.acme.order.ordering.config;

import com.acme.kernel.arch.ArchConfig;
import com.acme.order.ordering.domain.DiscountPolicy;
import com.acme.order.ordering.domain.Money;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the ordering module.
 *
 * <p>Assembly only. The threshold and rate are read from configuration and handed to the policy;
 * the decision about what to do with them stays in {@link DiscountPolicy}, where a plain unit test
 * can reach it. A rule implemented inside a {@code @Bean} method is a rule no test will ever see.
 */
@ArchConfig
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderingProperties.class)
public class OrderingConfig {

    @Bean
    public DiscountPolicy discountPolicy(OrderingProperties properties) {
        return new DiscountPolicy(
                Money.of(properties.getDiscountThreshold(), properties.getCurrency()),
                new BigDecimal(properties.getDiscountRate()));
    }
}
