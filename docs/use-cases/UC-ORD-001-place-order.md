# UC-ORD-001 — Place an order

| | |
|---|---|
| **Actor** | Customer |
| **Trigger** | `POST /orders` |
| **Implemented by** | `PlaceOrderService` |
| **Publishes** | `OrderPlaced` v1 |

## Intent
A customer commits to buying a set of items at the prices shown to them, and receives an identifier
they can use to track and cancel the order.

## Preconditions
- The caller is authenticated and the customer identifier is taken from the principal, never from
  the request body.
- Every line references a sellable SKU with a positive quantity.

## Rules
1. An order has at least one line. An empty order is refused, not stored as a draft.
2. Every line is priced in the same currency. Mixed currencies are refused rather than converted -
   an implicit conversion at an unstated rate is a financial defect.
3. Prices are captured at placement. A later price change does not alter a placed order.
4. Orders above the configured threshold receive the large-order discount, applied to the subtotal.

## Success
`201 Created`, a `Location` header pointing at the order, and a body carrying the order identifier.
`OrderPlaced` is published after the transaction commits.

## Failures
| Condition | `ErrorKind` | Code | Status |
|---|---|---|---|
| No lines | BUSINESS_RULE | `order.no-lines` | 422 |
| Lines in different currencies | BUSINESS_RULE | `order.mixed-currencies` | 422 |
| Malformed request body | VALIDATION | `validation.failed` | 400 |

## Notes
The event is published through `ApplicationEventPublisher`, so the state change and the
announcement commit together. See [P-072](../principles/P-072-transactional-outbox.md).
