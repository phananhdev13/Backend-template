-- Backs AgentActivationAuditListener, the platform's first real Kafka consumer.
create table agent_activation_audit (
    id            bigserial       not null primary key,
    agent_id      varchar(64)     not null,
    version_number integer        not null,
    activated_at  timestamp with time zone not null,
    recorded_at   timestamp with time zone not null default now()
);

create index idx_agent_activation_audit_agent_id on agent_activation_audit (agent_id);
