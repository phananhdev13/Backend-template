package com.acme.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the correlation identifier for the duration of one HTTP request.
 *
 * <p>Takes the inbound {@link Correlation#HEADER} when the caller supplied one that is safe to
 * echo and to log, so a trace started by the gateway or by an upstream service continues here
 * rather than restarting. Mints a UUID otherwise: a request with no identifier is a request whose
 * log lines cannot be grouped, and the cost of minting one is a UUID per request.
 *
 * <p>The identifier is put in the SLF4J {@link MDC} before the chain runs, which is what makes it
 * appear on log lines written by code that has never heard of this class, and it is echoed on the
 * response so that the identifier a client quotes in a support ticket is the one that finds the
 * log line.
 *
 * <p><strong>The {@code finally} block is the point of this class.</strong> Servlet containers
 * pool request threads and the MDC is a thread-local. An identifier left behind when a request
 * finishes is inherited by the next request served on that thread, and every line it logs is then
 * filed under a stranger's identifier. That failure is invisible in development, where one request
 * runs at a time, and it corrupts exactly the evidence an incident depends on. Clearing on the way
 * out, unconditionally, is the only thing that prevents it.
 *
 * <p>Ordered ahead of everything else, and in particular ahead of Spring Security's chain (which
 * registers at order -100): an authentication failure is a response, it gets logged, and a log
 * line without an identifier is the one you most want to join to the client's report.
 */
public final class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * Runs before Spring Security's {@code springSecurityFilterChain} at -100, and before the
     * servlet filters Boot registers around the dispatcher, so that rejections logged by any of
     * them are already correlated.
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /**
     * Where the identifier is parked for the ASYNC and ERROR dispatches of the same request.
     *
     * <p>Without it, a request whose identifier was minted rather than supplied would mint a
     * second, different one on its error dispatch - so the identifier in the response header and
     * the identifier on the log line that explains the failure would disagree, which is worse than
     * having neither.
     */
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = resolve(request);
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);

        MDC.put(Correlation.MDC_KEY, correlationId);
        response.setHeader(Correlation.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Correlation.MDC_KEY);
        }
    }

    /**
     * Also run on the ASYNC dispatch.
     *
     * <p>The default is to skip it, which means a controller returning a {@code DeferredResult} or
     * a {@code CompletableFuture} finishes its request on a thread with an empty MDC - so the half
     * of the request that is most likely to fail is the half that is not correlated.
     */
    private static String resolve(HttpServletRequest request) {
        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof String established) {
            return established;
        }
        return Correlation.accept(request.getHeader(Correlation.HEADER)).orElseGet(Correlation::newId);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * Also run on the ERROR dispatch.
     *
     * <p>The error dispatch happens after the original chain has returned and this filter has
     * already cleared the MDC. Skipping it, which is the default, means the response that actually
     * reaches a failing client is rendered and logged with no identifier at all.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
