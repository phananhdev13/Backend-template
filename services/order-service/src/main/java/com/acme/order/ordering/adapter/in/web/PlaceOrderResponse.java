package com.acme.order.ordering.adapter.in.web;

/** What the caller gets back: the identifier they will use for everything else. */
public record PlaceOrderResponse(String orderId) {}
