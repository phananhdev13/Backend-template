package com.acme.order;

import com.acme.archtest.AdapterRules;
import com.acme.archtest.AggregateRules;
import com.acme.archtest.BoundaryRules;
import com.acme.archtest.CacheContractRules;
import com.acme.archtest.ConfigRules;
import com.acme.archtest.DomainRules;
import com.acme.archtest.ErrorRules;
import com.acme.archtest.EventContractRules;
import com.acme.archtest.LayeringRules;
import com.acme.archtest.NamingRules;
import com.acme.archtest.ObservabilityRules;
import com.acme.archtest.OutboxRules;
import com.acme.archtest.PortRules;
import com.acme.archtest.ReadModelRules;
import com.acme.archtest.ResilienceRules;
import com.acme.archtest.RoleRules;
import com.acme.archtest.SchedulingRules;
import com.acme.archtest.SecurityRules;
import com.acme.archtest.TaskContractRules;
import com.acme.archtest.TraceabilityRules;
import com.acme.archtest.UseCaseRules;
import com.acme.archtest.ValueObjectRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ArchTests;

/**
 * The architecture, as a test.
 *
 * <p>Every service carries this file and nothing else: the rules live in {@code libs/arch-test} so
 * that a rule added there applies everywhere at once, and so that no service can quietly weaken one
 * for itself.
 *
 * <p>Run it alone while iterating:
 *
 * <pre>{@code mvn -pl services/order-service -am test -Dtest=ArchitectureTest}</pre>
 */
@AnalyzeClasses(packages = "com.acme.order", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchTests roles = ArchTests.in(RoleRules.class);

    @ArchTest
    static final ArchTests layering = ArchTests.in(LayeringRules.class);

    @ArchTest
    static final ArchTests useCases = ArchTests.in(UseCaseRules.class);

    @ArchTest
    static final ArchTests naming = ArchTests.in(NamingRules.class);

    @ArchTest
    static final ArchTests eventContracts = ArchTests.in(EventContractRules.class);

    @ArchTest
    static final ArchTests traceability = ArchTests.in(TraceabilityRules.class);

    @ArchTest
    static final ArchTests boundaries = ArchTests.in(BoundaryRules.class);

    @ArchTest
    static final ArchTests errors = ArchTests.in(ErrorRules.class);

    @ArchTest
    static final ArchTests readModels = ArchTests.in(ReadModelRules.class);

    @ArchTest
    static final ArchTests resilience = ArchTests.in(ResilienceRules.class);

    @ArchTest
    static final ArchTests observability = ArchTests.in(ObservabilityRules.class);

    @ArchTest
    static final ArchTests aggregates = ArchTests.in(AggregateRules.class);

    @ArchTest
    static final ArchTests valueObjects = ArchTests.in(ValueObjectRules.class);

    @ArchTest
    static final ArchTests domain = ArchTests.in(DomainRules.class);

    @ArchTest
    static final ArchTests ports = ArchTests.in(PortRules.class);

    @ArchTest
    static final ArchTests adapters = ArchTests.in(AdapterRules.class);

    @ArchTest
    static final ArchTests configuration = ArchTests.in(ConfigRules.class);

    @ArchTest
    static final ArchTests outbox = ArchTests.in(OutboxRules.class);

    @ArchTest
    static final ArchTests security = ArchTests.in(SecurityRules.class);

    @ArchTest
    static final ArchTests cacheContracts = ArchTests.in(CacheContractRules.class);

    @ArchTest
    static final ArchTests taskContracts = ArchTests.in(TaskContractRules.class);

    @ArchTest
    static final ArchTests scheduling = ArchTests.in(SchedulingRules.class);
}
