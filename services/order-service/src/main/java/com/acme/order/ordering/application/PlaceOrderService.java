package com.acme.order.ordering.application;

import com.acme.kernel.arch.UseCase;
import com.acme.order.ordering.application.port.in.PlaceOrderCommand;
import com.acme.order.ordering.application.port.in.PlaceOrderUseCase;
import com.acme.order.ordering.application.port.out.OrderRepository;
import com.acme.order.ordering.domain.DiscountPolicy;
import com.acme.order.ordering.domain.Order;
import com.acme.order.ordering.domain.OrderId;
import com.acme.order.ordering.domain.OrderPlaced;
import io.micrometer.observation.annotation.Observed;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * Load, decide, save, announce - and nothing else.
 *
 * <p>Every business rule in this operation lives in {@link Order} or {@link DiscountPolicy}. If a
 * rule appeared here instead, a second caller reaching the same aggregate would not get it, and the
 * two paths would drift apart quietly.
 *
 * <p>The event goes to {@link ApplicationEventPublisher}, not to a broker. Spring Modulith records
 * the pending publication in this same transaction and delivers after it commits. Sending straight
 * to Kafka here would publish "order placed" for an order that a later constraint violation rolls
 * back - a message no consumer can un-see.
 */
@UseCase(id = "UC-ORD-001", value = "A customer places an order")
@Transactional
public class PlaceOrderService implements PlaceOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlaceOrderService.class);

    private final OrderRepository orders;
    private final ApplicationEventPublisher events;
    private final DiscountPolicy discountPolicy;
    private final Clock clock;

    public PlaceOrderService(
            OrderRepository orders, ApplicationEventPublisher events, DiscountPolicy discountPolicy, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.discountPolicy = discountPolicy;
        this.clock = clock;
    }

    /**
     * Two different observability mechanisms, on purpose, per ADR-0019: {@code @Observed} feeds
     * Micrometer/Actuator - this repository's metrics pipeline, tagged {@code contextualName} so
     * "p99 by use case" is a query, not a grep. {@code @WithSpan} feeds a trace, but only when this
     * process is launched with {@code -javaagent:opentelemetry-javaagent.jar}; without the agent it
     * compiles and runs and creates nothing, silently. Neither annotation can enrich a span with
     * this use case's own generated id, since {@code @SpanAttribute} only reads a method's incoming
     * parameters and {@code orderId} does not exist until after {@link Order#place} runs - so it is
     * added to the already-open span with the plain OpenTelemetry API instead.
     */
    @Override
    @Observed(name = "usecase.place-order", contextualName = "UC-ORD-001")
    @WithSpan("usecase.UC-ORD-001")
    public OrderId placeOrder(PlaceOrderCommand command) {
        Order order = Order.place(OrderId.newId(), command.customerId(), command.lines(), clock);
        orders.save(order);
        Span.current().setAttribute("orderId", order.id().value().toString());

        var total = order.total(discountPolicy);
        events.publishEvent(new OrderPlaced(
                order.id().value(),
                order.customerId().value(),
                total.amount(),
                total.currency().getCurrencyCode(),
                Instant.now(clock)));

        log.info(
                "UC-ORD-001 placed order orderId={} lines={}",
                order.id().value(),
                order.lines().size());
        return order.id();
    }
}
