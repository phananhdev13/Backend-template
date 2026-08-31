package com.acme.web;

import java.net.URI;

/**
 * Builds the {@code type} URI of an RFC 9457 problem response from a domain error code.
 *
 * <p>The URI is the part clients branch on, which makes it API surface: renaming a code is a
 * breaking change in the same way that renaming a field is. Deriving it mechanically from the code
 * keeps the two from drifting.
 */
public final class ProblemTypes {

    /** Base for every problem type this platform emits. */
    public static final String BASE = "https://errors.acme.example/";

    private ProblemTypes() {}

    /** The type URI for a domain error code such as {@code order.already-cancelled}. */
    public static URI of(String code) {
        return URI.create(BASE + code);
    }
}
