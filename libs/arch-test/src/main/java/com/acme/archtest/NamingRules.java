package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.Adr;
import com.acme.kernel.arch.AggregateRoot;
import com.acme.kernel.arch.DomainEntity;
import com.acme.kernel.arch.DomainPolicy;
import com.acme.kernel.arch.DomainService;
import com.acme.kernel.arch.InboundAdapter;
import com.acme.kernel.arch.InputPort;
import com.acme.kernel.arch.OutboundAdapter;
import com.acme.kernel.arch.ReadModel;
import com.acme.kernel.arch.UseCase;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

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

    /**
     * Names ArchUnit cannot see: a method parameter's own name is not part of the imported model,
     * so this checks the type only, not "a String named {@code email}". A blanket ban on these
     * types in domain signatures is deliberately the stronger rule - see
     * docs/principles/P-021-illegal-states-unrepresentable.md for why.
     */
    private static final Set<String> PRIMITIVE_ISH_TYPES = Set.of(
            "java.lang.String",
            "java.math.BigDecimal",
            "java.util.UUID",
            "int",
            "long",
            "double",
            "float",
            "short",
            "byte",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Short",
            "java.lang.Byte");

    @ArchTest
    public static final ArchRule domainSignaturesUseValueObjects = classes()
            .that(playsADomainModellingRole())
            .should(takeNoPrimitiveIshParameters())
            .allowEmptyShould(true)
            .as("aggregates, entities, services and policies take value objects, not primitives (P-021)")
            .because("reserve(String orderId, String customerId) compiles when called with the "
                    + "arguments transposed, and the failure surfaces as a lookup that should never "
                    + "have been reachable. reserve(OrderId, CustomerId) makes the same mistake a "
                    + "compile error. See docs/principles/P-021-illegal-states-unrepresentable.md");

    private static DescribedPredicate<JavaClass> playsADomainModellingRole() {
        return new DescribedPredicate<>("is an @AggregateRoot, @DomainEntity, @DomainService or @DomainPolicy") {
            @Override
            public boolean test(JavaClass type) {
                return Annotations.has(type, AggregateRoot.class)
                        || Annotations.has(type, DomainEntity.class)
                        || Annotations.has(type, DomainService.class)
                        || Annotations.has(type, DomainPolicy.class);
            }
        };
    }

    private static ArchCondition<JavaClass> takeNoPrimitiveIshParameters() {
        return new ArchCondition<>(
                "take no String/BigDecimal/UUID/primitive-numeric parameters " + "on public methods") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (Annotations.has(item, Adr.class)) {
                    return;
                }
                for (JavaMethod method : item.getMethods()) {
                    if (!method.getModifiers().contains(JavaModifier.PUBLIC)
                            || method.getName().contains("$")) {
                        continue;
                    }
                    if (Annotations.hasNamed(method, Adr.class.getName())) {
                        continue;
                    }
                    method.getRawParameterTypes().stream()
                            .filter(parameterType -> PRIMITIVE_ISH_TYPES.contains(parameterType.getName()))
                            .forEach(parameterType -> events.add(SimpleConditionEvent.violated(
                                    item,
                                    ("%s.%s(...) takes a %s parameter. Wrap it in a @ValueObject so an "
                                                    + "argument transposition or an out-of-range value is a "
                                                    + "compile error instead of a runtime bug, or add @Adr if "
                                                    + "this one is genuinely unconstrained (a free-text note, an "
                                                    + "opaque vendor token).")
                                            .formatted(item.getName(), method.getName(), parameterType.getName()))));
                }
            }
        };
    }

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
