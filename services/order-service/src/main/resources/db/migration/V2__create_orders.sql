-- Initial schema for the ordering module.
--
-- Additive from here on: expand, migrate, contract. This file is never edited once applied -
-- Flyway checksums it, and every environment that already ran it would refuse to start.
create table orders (
    id          varchar(64)              not null primary key,
    customer_id varchar(64)              not null,
    status      varchar(32)              not null,
    placed_at   timestamp with time zone not null,
    version     bigint                   not null,
    created_at  timestamp with time zone not null,
    updated_at  timestamp with time zone not null
);

create table order_line (
    id                  bigserial      not null primary key,
    order_id            varchar(64)    not null references orders (id),
    sku                 varchar(64)    not null,
    quantity            integer        not null check (quantity > 0),
    unit_price_amount   numeric(19, 4) not null check (unit_price_amount >= 0),
    unit_price_currency varchar(3)     not null
);

-- The order summary query filters by customer and sorts by recency; without this it is a
-- sequential scan that gets slower every day the service runs.
create index idx_orders_customer_placed_at on orders (customer_id, placed_at desc);
create index idx_order_line_order_id on order_line (order_id);
