-- Backs ProvisionAgentDeploymentWorker, the platform's first real RabbitMQ classic-queue consumer.
-- The unique constraint is what makes the upsert in the worker idempotent: a redelivered task
-- inserts nothing new rather than a second row for the same activation.
create table agent_deployment (
    id              bigserial       not null primary key,
    agent_id        varchar(64)     not null,
    version_number  integer         not null,
    provisioned_at  timestamp with time zone not null,
    unique (agent_id, version_number)
);
