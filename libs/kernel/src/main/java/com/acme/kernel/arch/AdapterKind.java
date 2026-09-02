package com.acme.kernel.arch;

/** The technology family an adapter speaks, used to target technology-specific rules. */
public enum AdapterKind {
    REST(false),
    GRAPHQL(false),
    MESSAGING(true),
    SCHEDULER(false),
    PERSISTENCE(false),
    HTTP_CLIENT(true),
    CACHE(true),
    BLOB_STORAGE(true),
    RPC(true),
    WORKFLOW(true),
    IN_MEMORY(false);

    private final boolean remote;

    AdapterKind(boolean remote) {
        this.remote = remote;
    }

    /**
     * Whether an adapter of this kind talks to something over a network, and therefore owes a
     * timeout and a stated failure behaviour ({@link
     * com.acme.kernel.arch.ImplementsPrinciple P-051}).
     *
     * <p>The answer lives here rather than in the rule that asks it, so that adding a constant
     * forces a decision at the point of declaration. It used to be a list inside
     * {@code ResilienceRules}, which meant a new outbound technology - a search client, an SMTP
     * gateway - was simply absent from that list and silently escaped the resilience gate with
     * nothing to notice. A new constant now cannot compile without answering the question.
     *
     * <p>{@code PERSISTENCE} is deliberately false: a database call is remote, but its budget is
     * set by the connection pool and statement timeout rather than per adapter, and P-051 covers
     * it separately. {@code WORKFLOW} is true for the outbound direction - starting or signalling
     * a workflow is a call to the Temporal service.
     */
    public boolean isRemote() {
        return remote;
    }
}
