package com.acme.agentfactory.registry.domain;

/** Where one version of an agent is in its lifecycle. */
public enum AgentVersionStatus {

    /** Registered, not serving anything. Safe to iterate on. */
    DRAFT,

    /** The version currently in effect. At most one per agent. */
    ACTIVE,

    /** Was once active; superseded by a later activation. */
    DEPRECATED
}
