package com.acme.order;

import com.acme.kernel.arch.Command;
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
 * Entry point, and the one place that reconciles a framework-free application layer with a
 * framework that finds beans by annotation.
 *
 * <p>{@code @UseCase} and the other role annotations live in {@code libs/kernel}, which has no
 * Spring dependency and never will. They are therefore not stereotypes, and Spring would not find
 * them. The include filter below is what makes them beans - the cost of keeping the domain and
 * application layers importable without a framework on the classpath, paid once, here.
 */
@Modulith(systemName = "order-service")
@SpringBootApplication
@ComponentScan(
        includeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = {
                            UseCase.class,
                            ReadModel.class,
                            OutboundAdapter.class,
                            InboundAdapter.class,
                            Command.class
                        }))
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    /**
     * Time is a dependency, not an ambient fact.
     *
     * <p>Domain code takes a {@code Clock} so that "an order placed yesterday" is something a test
     * can arrange, rather than something that only happens if the suite runs at the right moment.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
