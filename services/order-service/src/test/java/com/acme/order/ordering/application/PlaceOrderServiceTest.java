package com.acme.order.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.order.ordering.application.port.in.PlaceOrderCommand;
import com.acme.order.ordering.application.port.out.OrderRepository;
import com.acme.order.ordering.domain.CustomerId;
import com.acme.order.ordering.domain.DiscountPolicy;
import com.acme.order.ordering.domain.Money;
import com.acme.order.ordering.domain.Order;
import com.acme.order.ordering.domain.OrderId;
import com.acme.order.ordering.domain.OrderLine;
import com.acme.order.ordering.domain.OrderPlaced;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Orchestration, with the ports substituted.
 *
 * <p>No Spring context and no mocking framework: the ports are small enough to implement by hand,
 * which keeps the test readable and removes a whole class of "the mock was configured wrongly"
 * failures. This is possible only because the ports speak domain language.
 */
class PlaceOrderServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC);

    private final Map<String, Order> saved = new HashMap<>();
    private final List<Object> published = new ArrayList<>();

    private final OrderRepository orders = new OrderRepository() {
        @Override
        public void save(Order order) {
            saved.put(order.id().value(), order);
        }

        @Override
        public Optional<Order> findById(OrderId id) {
            return Optional.ofNullable(saved.get(id.value()));
        }
    };

    private final ApplicationEventPublisher events = published::add;

    private final PlaceOrderService service = new PlaceOrderService(
            orders, events, new DiscountPolicy(Money.of("500.00", "EUR"), new BigDecimal("0.05")), FIXED);

    @Test
    void placingAnOrderSavesItAndAnnouncesIt() {
        OrderId id = service.placeOrder(new PlaceOrderCommand(
                new CustomerId("cust-1"), List.of(new OrderLine("SKU-1", 2, Money.of("10.00", "EUR")))));

        assertThat(saved).containsKey(id.value());
        assertThat(published).hasSize(1).first().isInstanceOf(OrderPlaced.class);
    }

    @Test
    void theAnnouncedTotalIsTheDiscountedTotal() {
        service.placeOrder(new PlaceOrderCommand(
                new CustomerId("cust-1"), List.of(new OrderLine("SKU-1", 10, Money.of("100.00", "EUR")))));

        OrderPlaced event = (OrderPlaced) published.getFirst();
        // 1000.00 subtotal, above the 500.00 threshold, less 5%.
        assertThat(event.totalAmount()).isEqualByComparingTo("950.00");
        assertThat(event.currency()).isEqualTo("EUR");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-30T10:00:00Z"));
    }
}
