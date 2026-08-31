package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.DomainPolicy;
import com.acme.kernel.arch.InboundAdapter;
import com.acme.kernel.arch.InputPort;
import com.acme.kernel.arch.OutboundAdapter;
import com.acme.kernel.arch.ReadModel;
import com.acme.kernel.arch.UseCase;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Names say what a thing is: P-010.
 *
 * <p>Naming rules earn their keep when a codebase is read by someone - or something -
 * that has not opened the file yet. A consistent suffix means a search finds every use
 * case, and a reviewer looking at a diff knows the rules that apply to
 * {@code PlaceOrderService} without leaving the hunk.
 *
 * <p>The convention: the port is {@code PlaceOrderUseCase}, the class implementing it is
 * {@code PlaceOrderService}. Callers name the capability; the implementation is an
 * implementation.
 */
public final class NamingRules {

    @ArchTest
    public static final ArchRule inputPortsEndWithUseCase = classes()
            .that()
            .areAnnotatedWith(InputPort.class)
            .should()
            .haveSimpleNameEndingWith("UseCase")
            .allowEmptyShould(true)
            .as("input ports are named <Verb><Noun>UseCase (P-010)");

    @ArchTest
    public static final ArchRule useCasesEndWithService = classes()
            .that()
            .areAnnotatedWith(UseCase.class)
            .should()
            .haveSimpleNameEndingWith("Service")
            .allowEmptyShould(true)
            .as("use case implementations are named <Verb><Noun>Service (P-010)");

    @ArchTest
    public static final ArchRule readModelsEndWithQuery = classes()
            .that()
            .areAnnotatedWith(ReadModel.class)
            .should()
            .haveSimpleNameEndingWith("Query")
            .allowEmptyShould(true)
            .as("read models are named <Noun>Query (P-032)");

    @ArchTest
    public static final ArchRule restAdaptersEndWithController = classes()
            .that(annotatedWithInboundKind(AdapterKind.REST))
            .should()
            .haveSimpleNameEndingWith("Controller")
            .allowEmptyShould(true)
            .as("REST inbound adapters are named <Noun>Controller (P-040)");

    @ArchTest
    public static final ArchRule outboundAdaptersEndWithAdapter = classes()
            .that()
            .areAnnotatedWith(OutboundAdapter.class)
            .should()
            .haveSimpleNameEndingWith("Adapter")
            .allowEmptyShould(true)
            .as("outbound adapters are named <Technology><Port>Adapter (P-041)");

    @ArchTest
    public static final ArchRule policiesEndWithPolicy = classes()
            .that()
            .areAnnotatedWith(DomainPolicy.class)
            .should()
            .haveSimpleNameEndingWith("Policy")
            .allowEmptyShould(true)
            .as("domain policies are named <Decision>Policy (P-022)");

    @ArchTest
    public static final ArchRule edgeDataTypesStayInAdapters = classes()
            .that(isEdgeDataType())
            .should()
            .resideInAPackage("..adapter..")
            .allowEmptyShould(true)
            .as("Request, Response and Dto types live only in adapters (P-040)")
            .because("a type named for serialisation inside the domain means the wire format has "
                    + "become the model. See docs/principles/P-040-inbound-adapters-translate.md");

    @ArchTest
    public static final ArchRule policiesDeclareWhatTheyDecide = classes()
            .that()
            .areAnnotatedWith(DomainPolicy.class)
            .should(stateTheirDecision())
            .allowEmptyShould(true)
            .as("a domain policy says what it decides (P-022)")
            .because("a policy whose name is its only description gets reused for a second decision, "
                    + "and the two drift apart. See docs/principles/P-022-domain-services-and-policies.md");

    private static ArchCondition<JavaClass> stateTheirDecision() {
        return new ArchCondition<>("declare what it decides") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Annotations.find(item, DomainPolicy.class).ifPresent(policy -> {
                    if (!Annotations.string(policy, "decides", "").isBlank()) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(
                            item, "%s is a @DomainPolicy with no `decides` description.".formatted(item.getName())));
                });
            }
        };
    }

    private NamingRules() {}

    private static DescribedPredicate<JavaClass> annotatedWithInboundKind(AdapterKind kind) {
        return new DescribedPredicate<>("annotated @InboundAdapter(" + kind + ")") {
            @Override
            public boolean test(JavaClass type) {
                return Annotations.find(type, InboundAdapter.class)
                        .map(annotation -> kind.name().equals(Annotations.enumName(annotation, "value", "")))
                        .orElse(false);
            }
        };
    }

    private static DescribedPredicate<JavaClass> isEdgeDataType() {
        return new DescribedPredicate<>("named as a serialisation-only type") {
            @Override
            public boolean test(JavaClass type) {
                return !Classes.structural().test(type) && Classes.isEdgeData(type);
            }
        };
    }
}
