package com.acme.order.ordering.adapter.in.web;

import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.InboundAdapter;
import com.acme.kernel.error.NotFoundException;
import com.acme.order.ordering.application.OrderSummaryQuery;
import com.acme.order.ordering.application.port.in.PlaceOrderCommand;
import com.acme.order.ordering.application.port.in.PlaceOrderUseCase;
import com.acme.order.ordering.domain.CustomerId;
import com.acme.order.ordering.domain.Money;
import com.acme.order.ordering.domain.OrderId;
import com.acme.order.ordering.domain.OrderLine;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP edge for orders: parse, map, call, map back.
 *
 * <p>There is no business decision in this class, and that is checkable rather than aspirational -
 * the controller depends on {@link PlaceOrderUseCase}, the port, never on the class implementing
 * it. Anything decided here would be invisible to the message listener that reaches the same use
 * case, and would be reimplemented, differently, the first time one is added.
 *
 * <p>No exception is caught. {@code DomainExceptionHandler} in {@code libs/web-support} turns a
 * domain failure into an RFC 9457 problem response, once, for every endpoint in every service.
 */
@InboundAdapter(AdapterKind.REST)
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final PlaceOrderUseCase placeOrder;
    private final OrderSummaryQuery summaries;

    public OrderController(PlaceOrderUseCase placeOrder, OrderSummaryQuery summaries) {
        this.placeOrder = placeOrder;
        this.summaries = summaries;
    }

    @PostMapping
    ResponseEntity<PlaceOrderResponse> place(@Valid @RequestBody PlaceOrderRequest request) {
        OrderId id = placeOrder.placeOrder(toCommand(request));
        return ResponseEntity.created(URI.create("/orders/" + id.value())).body(new PlaceOrderResponse(id.value()));
    }

    @GetMapping("/{orderId}")
    OrderSummaryQuery.OrderSummary byId(@PathVariable String orderId) {
        return summaries.byId(orderId).orElseThrow(() -> NotFoundException.of("Order", orderId));
    }

    @GetMapping
    List<OrderSummaryQuery.OrderSummary> forCustomer(
            @RequestParam String customerId, @RequestParam(defaultValue = "50") int limit) {
        // Page size is capped here rather than trusted from the caller: an unbounded limit is a
        // denial of service one query string away.
        return summaries.forCustomer(customerId, Math.min(limit, 200));
    }

    /**
     * The only place strings and decimals become domain types.
     *
     * <p>Past this line an unparseable currency or a negative quantity does not exist, so no use
     * case downstream has to remember to check for one.
     */
    private static PlaceOrderCommand toCommand(PlaceOrderRequest request) {
        List<OrderLine> lines = request.lines().stream()
                .map(line -> new OrderLine(line.sku(), line.quantity(), Money.of(line.unitPrice(), request.currency())))
                .toList();
        return new PlaceOrderCommand(new CustomerId(request.customerId()), lines);
    }
}
