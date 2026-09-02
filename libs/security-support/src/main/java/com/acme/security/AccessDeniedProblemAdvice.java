package com.acme.security;

import com.acme.web.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The second half of "one exception translation point" (P-050, {@code web-support}'s own
 * {@code DomainExceptionHandler}) - added here, not there, because only a service that depends
 * on {@code security-support} ever has {@link AccessDeniedException} on its classpath at all.
 *
 * <p>A {@code @PreAuthorize} denial on a {@code @UseCase} throws this exception from inside the
 * method-security AOP advice wrapping the use case call - which happens *after* Spring's
 * {@code DispatcherServlet} has already dispatched to the controller, so it reaches an ordinary
 * {@code @RestControllerAdvice} exactly like a thrown {@code DomainException} does. This is the
 * opposite path from {@code SecuritySupportAutoConfiguration}'s {@code AccessDeniedHandler},
 * which exists for a denial the servlet filter chain itself decides - reachable only for a rule
 * written directly into {@code authorizeHttpRequests}, which this platform does not do (P-120
 * puts every authorisation decision at the use case, never at a URL pattern).
 */
@RestControllerAdvice
public class AccessDeniedProblemAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail onAccessDenied(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "The caller may not do this");
        problem.setType(ProblemTypes.of("auth.forbidden"));
        problem.setTitle(HttpStatus.FORBIDDEN.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
