# QRY-ORD-001 — Order summaries

| | |
|---|---|
| **Actor** | Customer, operator |
| **Trigger** | `GET /orders?customerId=…`, `GET /orders/{orderId}` |
| **Implemented by** | `OrderSummaryQuery` |

## Intent
List a customer's recent orders, or fetch one, with enough detail for a list screen and no more.

## Shape
`orderId`, `customerId`, `status`, `subtotal`.

## Why this bypasses the domain
Rendering a list by hydrating aggregates loads every line of every order to display a total, and
turns one indexed query into an N+1. A read model changes nothing, so it is allowed to project
straight from the database — and `ReadModelRules.readModelsHaveNoSideEffects` keeps that true as
the class is edited. See [P-032](../principles/P-032-reads-and-writes-shaped-separately.md).

## Constraints
- Page size is capped server-side at 200 regardless of the requested limit.
- Ordering is by placement time, descending, served by `idx_orders_customer_placed_at`.
- A caller may only list their own orders; the customer identifier comes from the principal.
