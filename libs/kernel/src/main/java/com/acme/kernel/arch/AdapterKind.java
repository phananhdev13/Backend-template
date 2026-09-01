package com.acme.kernel.arch;

/** The technology family an adapter speaks, used to target technology-specific rules. */
public enum AdapterKind {
    REST,
    GRAPHQL,
    MESSAGING,
    SCHEDULER,
    PERSISTENCE,
    HTTP_CLIENT,
    CACHE,
    BLOB_STORAGE,
    RPC,
    IN_MEMORY
}
