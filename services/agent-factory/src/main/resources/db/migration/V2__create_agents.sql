-- Initial schema for the agent registry.
--
-- Additive from here on: expand, migrate, contract. This file is never edited once applied.
create table agent_definition (
    id          varchar(64)              not null primary key,
    name        varchar(200)             not null unique,
    version     bigint                   not null,
    created_at  timestamp with time zone not null,
    updated_at  timestamp with time zone not null
);

create table agent_version (
    id              bigserial       not null primary key,
    agent_id        varchar(64)     not null references agent_definition (id),
    version_number  integer         not null,
    provider        varchar(100)    not null,
    model_id        varchar(200)    not null,
    system_prompt   text            not null,
    status          varchar(32)     not null,
    created_at      timestamp with time zone not null,
    unique (agent_id, version_number)
);

create table agent_version_tool (
    agent_version_id bigint       not null references agent_version (id),
    tool_name        varchar(200) not null
);

-- The registry summary lists agents by name and looks up one active version per agent; both are
-- covered by this pair of indexes rather than a sequential scan per request.
create index idx_agent_version_agent_id on agent_version (agent_id);
create index idx_agent_version_active on agent_version (agent_id, status);
