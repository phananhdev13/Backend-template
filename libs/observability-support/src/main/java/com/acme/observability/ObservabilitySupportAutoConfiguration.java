package com.acme.observability;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Wires the correlation filter into every servlet application on the classpath, without asking.
 *
 * <p>An opt-in would be wrong here. Correlation is only worth anything if it is present on every
 * hop; a service that forgot the annotation is a hole in the trace that is discovered during the
 * incident it was meant to help with. Registered as an auto-configuration, adding
 * {@code observability-support} to a pom is the whole of the wiring.
 *
 * <p>The filter is exposed only through its {@link FilterRegistrationBean}, never as a bare
 * {@code Filter} bean. Boot registers loose {@code Filter} beans itself, at the default order,
 * which would place the filter after Spring Security's chain and lose exactly the log lines the
 * ordering exists to keep.
 *
 * <p>{@link ConditionalOnMissingBean} on the registration leaves a service free to replace the
 * whole registration - to change the order, restrict the dispatcher types, or substitute a filter
 * that also seeds a vendor tracing context - by declaring its own.
 */
@AutoConfiguration
@ConditionalOnClass(Filter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ObservabilitySupportAutoConfiguration {

    /** Bean name a service overrides, or refers to when ordering its own filters around it. */
    public static final String FILTER_REGISTRATION_BEAN = "correlationIdFilterRegistration";

    @Bean(name = FILTER_REGISTRATION_BEAN)
    @ConditionalOnMissingBean(name = FILTER_REGISTRATION_BEAN)
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setName("correlationIdFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(CorrelationIdFilter.ORDER);
        // REQUEST alone would leave the two dispatches where things actually go wrong
        // uncorrelated: the ASYNC completion of a DeferredResult, and the ERROR dispatch that
        // renders whatever the request failed with.
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
