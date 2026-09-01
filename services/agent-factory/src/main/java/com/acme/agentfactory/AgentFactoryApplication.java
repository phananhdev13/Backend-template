package com.acme.agentfactory;

import com.acme.kernel.arch.InboundAdapter;
import com.acme.kernel.arch.OutboundAdapter;
import com.acme.kernel.arch.ReadModel;
import com.acme.kernel.arch.UseCase;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.modulith.Modulith;

/**
 * Entry point for the platform's second service - registers AI agent definitions and their
 * versions, and announces when one is activated.
 *
 * <p>See {@code com.acme.order.OrderServiceApplication} for why this include filter exists: the
 * role annotations in {@code libs/kernel} carry no Spring dependency, so they are not stereotypes
 * and would otherwise be invisible to component scanning. {@code @Command} is deliberately absent
 * - a command is a plain data record passed as a method argument, never instantiated by Spring.
 */
@Modulith(systemName = "agent-factory")
@SpringBootApplication
@ComponentScan(
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = {UseCase.class, ReadModel.class, OutboundAdapter.class, InboundAdapter.class}))
public class AgentFactoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentFactoryApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
