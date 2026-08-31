package com.acme.observability;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * The one name and the one storage location for the request correlation identifier.
 *
 * <p>Both constants exist so that nothing spells them a second time. A header written as
 * {@code X-Correlation-ID} in one service and {@code X-Correlation-Id} in the next still matches
 * on the wire - HTTP header names are case-insensitive - but an MDC key written as
 * {@code correlation_id} in one appender configuration and {@code correlationId} in another
 * silently produces logs that cannot be joined, and nothing fails until someone tries to trace a
 * production incident across both services.
 *
 * <p>The value is read from the MDC rather than passed as a parameter because the alternative is
 * threading an identifier through every signature in the call stack, which is abandoned the first
 * time somebody is in a hurry, leaving exactly the gap that matters.
 */
public final class Correlation {

    /** Inbound and outbound header carrying the identifier across an HTTP hop. */
    public static final String HEADER = "X-Correlation-Id";

    /** SLF4J {@link MDC} key. Log appender patterns and JSON encoders refer to this name. */
    public static final String MDC_KEY = "correlationId";

    /**
     * What an inbound identifier is allowed to look like.
     *
     * <p>The value is echoed into a response header and written into every log line for the
     * request, so it is attacker-controlled data reaching two sinks. A newline in it splits the
     * response header or forges a log entry; an unbounded value fills the log budget. Anything
     * outside this alphabet is discarded and a fresh identifier is minted instead - dropping a
     * caller's identifier costs one broken trace, accepting a hostile one costs the integrity of
     * the log.
     */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._:@=+-]{1,128}");

    private Correlation() {}

    /**
     * The identifier for the request being served on this thread, if one has been established.
     *
     * <p>Empty outside a request - a scheduled job, a startup hook, a test - which is why this
     * returns an {@code Optional} rather than a possibly-null string that some caller will
     * concatenate into a log message as {@code "null"}.
     */
    public static Optional<String> current() {
        return accept(MDC.get(MDC_KEY));
    }

    /**
     * The current identifier, or a new one when there is none.
     *
     * <p>For code that must stamp an outgoing message or an audit record with something: a fresh
     * identifier that correlates nothing is still better than a null that breaks the consumer's
     * parsing or, worse, an empty string that makes every uncorrelated record look like one
     * conversation.
     */
    public static String currentOrNew() {
        return current().orElseGet(Correlation::newId);
    }

    /** A fresh identifier. Package-private: minting one is the filter's job, at the edge. */
    static String newId() {
        return UUID.randomUUID().toString();
    }

    /** The candidate if it is safe to echo and to log, otherwise empty. */
    static Optional<String> accept(@Nullable String candidate) {
        if (candidate == null || !ACCEPTABLE.matcher(candidate).matches()) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }
}
