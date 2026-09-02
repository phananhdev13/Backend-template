package com.acme.archtest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.EventContract;
import com.acme.kernel.event.EventHandler;
import com.acme.kernel.event.Idempotent;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Event contracts must be internally consistent: P-070, P-071.
 *
 * <p>Every rule here catches a combination that compiles, deploys, and is wrong - and
 * wrong in a way that surfaces weeks later, in a consumer, as missing or duplicated
 * data. That distance between cause and symptom is exactly why these are build failures
 * rather than review comments.
 */
public final class EventContractRules {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final List<String> COMPACTING_RETENTIONS = List.of("COMPACTED", "COMPACTED_AND_WINDOWED");

    @ArchTest
    public static final ArchRule everyDomainEventDeclaresAContract = classes()
            .that()
            .areAssignableTo(DomainEvent.class)
            .and()
            .areNotInterfaces()
            .should(beAnnotatedWithEventContract())
            .allowEmptyShould(true)
            .as("every domain event declares its stream contract (P-070)")
            .because("an event with no declared key, retention or delivery guarantee leaves those "
                    + "decisions to whoever creates the topic. See docs/principles/P-070-event-semantics.md");

    @ArchTest
    public static final ArchRule partitionKeyExistsOnRecord = classes()
            .that()
            .areAnnotatedWith(EventContract.class)
            .should(declareTheirPartitionKeyAsAComponent())
            .allowEmptyShould(true)
            .as("the declared partition key is a real component of the event (P-070)")
            .because("a key naming a field that does not exist degrades to round-robin partitioning, "
                    + "and takes per-key ordering with it. See docs/principles/P-070-event-semantics.md");

    @ArchTest
    public static final ArchRule compactedStreamsCarryStateSnapshots = classes()
            .that()
            .areAnnotatedWith(EventContract.class)
            .should(notCompactAStreamOfFacts())
            .allowEmptyShould(true)
            .as("compacted streams carry state snapshots, never facts (P-070)")
            .because("compaction deletes superseded messages. On a stream of deltas that destroys the "
                    + "history a replay needs, and the loss is silent until someone replays. "
                    + "See docs/principles/P-070-event-semantics.md");

    @ArchTest
    public static final ArchRule everyContractHasASchemaFile = classes()
            .that()
            .areAnnotatedWith(EventContract.class)
            .should(referenceASchemaThatExists())
            .allowEmptyShould(true)
            .as("every event contract points at a schema file that exists (P-080)")
            .because("a published event is an API, and consumers in other repositories have nothing to "
                    + "generate from without it. See docs/principles/P-080-api-versioning.md");

    /**
     * The schema file states the same nine facts the annotation does, and lists the same fields the
     * record declares. Existence alone was checked, so the two copies could disagree - and the JSON
     * is the copy other repositories generate consumers from, while the annotation is the copy this
     * service actually behaves according to. A consumer built against a stale schema partitions on
     * the wrong key or expects a field that is gone, and nothing here would have said so.
     *
     * <p>This is the event-side equivalent of each service's {@code OpenApiContractTest}, which
     * already holds the checked-in OpenAPI document to what the controllers really produce.
     */
    @ArchTest
    public static final ArchRule schemaFilesAgreeWithTheirContract = classes()
            .that()
            .areAnnotatedWith(EventContract.class)
            .should(matchTheirSchemaFile())
            .allowEmptyShould(true)
            .as("an event's schema file states the same contract the annotation does (P-070, P-080)")
            .because("consumers in other repositories generate from the schema, and this service "
                    + "behaves according to the annotation. See docs/principles/P-070-event-semantics.md");

    /**
     * {@code ApplicationEventPublisher.publishEvent} takes an {@code Object}, so every rule in this
     * class - all of which key on {@code DomainEvent} or {@code @EventContract} - could be escaped
     * by publishing a plain record. It had no stream, no partition key, no retention and no schema
     * file, and it shipped through Modulith's publication registry like any other event.
     * {@code DomainEventPublisher} in the kernel takes a {@code DomainEvent}, which turns that from
     * something to detect into something that does not compile.
     */
    @ArchTest
    public static final ArchRule eventsArePublishedThroughTheTypedPublisher = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .or()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.context.ApplicationEventPublisher")
            .allowEmptyShould(true)
            .as("domain events are published through DomainEventPublisher (P-070)")
            .because("ApplicationEventPublisher.publishEvent accepts any Object, so a record with no "
                    + "@EventContract can be published and no rule here would see it. Inject "
                    + "com.acme.kernel.event.DomainEventPublisher instead. "
                    + "See docs/principles/P-070-event-semantics.md");

    @ArchTest
    public static final ArchRule atLeastOnceHandlersAreIdempotent = classes()
            .that()
            .areAnnotatedWith(EventHandler.class)
            .should(beIdempotentWhenDeliveryRepeats())
            .allowEmptyShould(true)
            .as("handlers of repeatable streams declare their idempotency (P-071)")
            .because("a rebalance or a broker retry redelivers, and a handler that charges a card or "
                    + "sends an email does it twice. See docs/principles/P-071-idempotency.md");

    @ArchTest
    public static final ArchRule unboundedChoicesAreJustified = classes()
            .that()
            .areAnnotatedWith(EventContract.class)
            .should(carryAnAdrForExpensiveGuarantees())
            .allowEmptyShould(true)
            .as("expensive delivery guarantees carry an ADR reference (P-070)")
            .because("infinite retention, global ordering and compacted personal data each impose a "
                    + "cost or an obligation someone has to own. "
                    + "See docs/principles/P-070-event-semantics.md");

    /**
     * Two events publishing to the same stream and version is a collision that no single class can
     * detect, so it is checked across the whole import.
     */
    @ArchTest
    public static void streamIdentifiersAreUnique(JavaClasses classes) {
        Map<String, List<String>> byIdentifier = new HashMap<>();
        for (JavaClass type : classes) {
            Annotations.find(type, EventContract.class).ifPresent(contract -> {
                String identifier = Annotations.string(contract, "stream", "?")
                        + " v"
                        + Annotations.integer(contract, "version", 1);
                byIdentifier
                        .computeIfAbsent(identifier, key -> new ArrayList<>())
                        .add(type.getName());
            });
        }
        List<String> collisions = byIdentifier.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " is declared by " + entry.getValue())
                .toList();
        if (!collisions.isEmpty()) {
            throw new AssertionError("Two event types claim the same stream and version, so consumers "
                    + "cannot tell them apart. Give the incompatible one a new version.\n  "
                    + String.join("\n  ", collisions));
        }
    }

    @ArchTest
    public static final ArchRule orderingPromisesRequireAKey = classes()
            .that()
            .areAnnotatedWith(EventContract.class)
            .should(backOrderingPromisesWithAKey())
            .allowEmptyShould(true)
            .as("an ordering promise is backed by a key (P-070)")
            .because("per-key ordering without a key is round-robin partitioning with a reassuring "
                    + "name. See docs/principles/P-070-event-semantics.md");

    @ArchTest
    public static final ArchRule idempotencyKeyIsStableAcrossRetries = classes()
            .that()
            .areAnnotatedWith(EventHandler.class)
            .and()
            .areAnnotatedWith(Idempotent.class)
            .should(keyTheirDeduplicationOnTheConsumedEvent())
            .allowEmptyShould(true)
            .as("the idempotency key is a field of the event being consumed (P-071)")
            .because("a key naming a field the event does not carry cannot be read at runtime, and a "
                    + "key generated per publish attempt de-duplicates nothing. "
                    + "See docs/principles/P-071-idempotency.md");

    private static ArchCondition<JavaClass> backOrderingPromisesWithAKey() {
        return new ArchCondition<>("declare a key when promising ordering") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Annotations.find(item, EventContract.class).ifPresent(contract -> {
                    String ordering = Annotations.enumName(contract, "ordering", "PER_KEY");
                    String key = Annotations.string(contract, "partitionKey", "");
                    if (!"PER_KEY".equals(ordering) || !key.isBlank()) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(
                            item,
                            "%s promises PER_KEY ordering with no partition key. Name the component "
                                            .formatted(item.getName())
                                    + "that identifies the sequence, or declare ordering = NONE."));
                });
            }
        };
    }

    private static ArchCondition<JavaClass> keyTheirDeduplicationOnTheConsumedEvent() {
        return new ArchCondition<>("de-duplicate on a field of the consumed event") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaClass> consumed =
                        Annotations.find(item, EventHandler.class).flatMap(a -> Annotations.type(a, "consumes"));
                Optional<JavaAnnotation<JavaClass>> idempotent = Annotations.find(item, Idempotent.class);
                if (consumed.isEmpty()
                        || idempotent.isEmpty()
                        || consumed.get().getAllFields().isEmpty()) {
                    return;
                }
                String key = Annotations.string(idempotent.get(), "key", "");
                if (!key.isBlank() && hasComponent(consumed.get(), key)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s de-duplicates on \"%s\", which %s does not carry."
                                .formatted(item.getName(), key, consumed.get().getSimpleName())));
            }
        };
    }

    private EventContractRules() {}

    private static boolean hasComponent(JavaClass type, String name) {
        boolean asField =
                type.getAllFields().stream().anyMatch(field -> field.getName().equals(name));
        boolean asAccessor = type.getAllMethods().stream()
                .anyMatch(method -> method.getName().equals(name)
                        && method.getRawParameterTypes().isEmpty());
        return asField || asAccessor;
    }

    private static ArchCondition<JavaClass> beAnnotatedWithEventContract() {
        return new ArchCondition<>("declare an @EventContract") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (Annotations.has(item, EventContract.class)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s implements DomainEvent but declares no @EventContract. Add one naming the "
                                        .formatted(item.getName())
                                + "stream, partition key, retention and delivery guarantee."));
            }
        };
    }

    private static ArchCondition<JavaClass> declareTheirPartitionKeyAsAComponent() {
        return new ArchCondition<>("declare a partition key that exists on the event") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaAnnotation<JavaClass>> contract = Annotations.find(item, EventContract.class);
                if (contract.isEmpty()) {
                    return;
                }
                String key = Annotations.string(contract.get(), "partitionKey", "");
                if (!key.isBlank() && hasComponent(item, key)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s declares partitionKey \"%s\", which is not a component of the record."
                                .formatted(item.getName(), key)));
            }
        };
    }

    private static ArchCondition<JavaClass> notCompactAStreamOfFacts() {
        return new ArchCondition<>("compact only streams of state snapshots") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaAnnotation<JavaClass>> contract = Annotations.find(item, EventContract.class);
                if (contract.isEmpty()) {
                    return;
                }
                String retention = Annotations.enumName(contract.get(), "retention", "TIME_WINDOW");
                String payload = Annotations.enumName(contract.get(), "payload", "FACT");
                if (!COMPACTING_RETENTIONS.contains(retention) || "STATE_SNAPSHOT".equals(payload)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s declares retention=%s with payload=FACT. Compaction keeps only the newest "
                                        + "message per key, so earlier facts are deleted and a replay cannot "
                                        + "rebuild state. Either publish the whole current state and set "
                                        + "payload=STATE_SNAPSHOT, or use retention=TIME_WINDOW.")
                                .formatted(item.getName(), retention)));
            }
        };
    }

    /** The nine {@code @EventContract} members the schema's {@code x-contract} block restates. */
    private static Map<String, Object> declaredContract(JavaAnnotation<JavaClass> contract) {
        Map<String, Object> declared = new java.util.LinkedHashMap<>();
        declared.put("stream", Annotations.string(contract, "stream", ""));
        declared.put("version", Annotations.integer(contract, "version", 1));
        declared.put("partitionKey", Annotations.string(contract, "partitionKey", ""));
        declared.put("payload", Annotations.enumName(contract, "payload", "FACT"));
        declared.put("retention", Annotations.enumName(contract, "retention", "TIME_WINDOW"));
        declared.put("retentionDays", Annotations.integer(contract, "retentionDays", 7));
        declared.put("delivery", Annotations.enumName(contract, "delivery", "AT_LEAST_ONCE"));
        declared.put("ordering", Annotations.enumName(contract, "ordering", "PER_KEY"));
        declared.put("containsPersonalData", Annotations.bool(contract, "containsPersonalData", false));
        return declared;
    }

    private static ArchCondition<JavaClass> matchTheirSchemaFile() {
        return new ArchCondition<>("agree with the checked-in schema file") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaAnnotation<JavaClass>> annotation = Annotations.find(item, EventContract.class);
                if (annotation.isEmpty()) {
                    return;
                }
                String schema = Annotations.string(annotation.get(), "schema", "");
                // everyContractHasASchemaFile already reports a missing or unnamed file; this rule
                // says nothing rather than repeating it.
                if (schema.isBlank() || !RepositoryLayout.exists(schema)) {
                    return;
                }
                JsonNode root;
                try {
                    root = MAPPER.readTree(
                            RepositoryLayout.root().resolve(schema).toFile());
                } catch (RuntimeException e) {
                    events.add(SimpleConditionEvent.violated(
                            item, "%s's schema %s is not readable JSON: %s".formatted(item.getName(), schema, e)));
                    return;
                }
                for (String difference : differences(item, root, annotation.get(), schema)) {
                    events.add(SimpleConditionEvent.violated(item, difference));
                }
            }
        };
    }

    private static List<String> differences(
            JavaClass item, JsonNode root, JavaAnnotation<JavaClass> annotation, String schema) {
        List<String> found = new ArrayList<>();
        JsonNode block = root.path("x-contract");
        if (block.isMissingNode()) {
            found.add(("%s's schema %s has no \"x-contract\" block. It is what states the stream's key, "
                            + "retention, delivery and ordering to a consumer in another repository.")
                    .formatted(item.getName(), schema));
            return found;
        }
        declaredContract(annotation).forEach((member, declared) -> {
            JsonNode node = block.path(member);
            String inSchema = node.isMissingNode() ? "(absent)" : node.asString();
            if (!String.valueOf(declared).equals(inSchema)) {
                found.add(("%s declares %s = %s but %s says %s. The annotation is what this service "
                                + "does; the schema is what other repositories build against.")
                        .formatted(item.getName(), member, declared, schema, inSchema));
            }
        });

        // additionalProperties:false means a consumer's validator rejects any field the schema does
        // not list, so a record component missing here is a breaking change the moment it is sent.
        // Sorted, not insertion-ordered: getAllFields() is a Set, so an unsorted message would
        // list the same fields in a different order between runs and read as flakiness.
        Set<String> components = item.getAllFields().stream()
                .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
                .map(field -> field.getName())
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        Set<String> properties = new java.util.TreeSet<>(root.path("properties").propertyNames());
        if (!properties.isEmpty() && !properties.equals(components)) {
            Set<String> undocumented = new java.util.TreeSet<>(components);
            undocumented.removeAll(properties);
            Set<String> phantom = new java.util.TreeSet<>(properties);
            phantom.removeAll(components);
            found.add(("%s carries %s but %s documents %s%s%s. A consumer validating against this "
                            + "schema rejects any field it does not list.")
                    .formatted(
                            item.getName(),
                            components,
                            schema,
                            properties,
                            undocumented.isEmpty() ? "" : " - missing " + undocumented,
                            phantom.isEmpty() ? "" : " - stale " + phantom));
        }
        return found;
    }

    private static ArchCondition<JavaClass> referenceASchemaThatExists() {
        return new ArchCondition<>("reference a schema file that exists") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaAnnotation<JavaClass>> contract = Annotations.find(item, EventContract.class);
                if (contract.isEmpty()) {
                    return;
                }
                String schema = Annotations.string(contract.get(), "schema", "");
                if (!schema.isBlank() && RepositoryLayout.exists(schema)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s references schema \"%s\", which does not exist in the repository."
                                .formatted(item.getName(), schema)));
            }
        };
    }

    private static ArchCondition<JavaClass> beIdempotentWhenDeliveryRepeats() {
        return new ArchCondition<>("be idempotent when the stream may redeliver") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaAnnotation<JavaClass>> handler = Annotations.find(item, EventHandler.class);
                if (handler.isEmpty()) {
                    return;
                }
                Optional<JavaAnnotation<JavaClass>> contract = Annotations.type(handler.get(), "consumes")
                        .flatMap(consumed -> Annotations.find(consumed, EventContract.class));
                if (contract.isEmpty()) {
                    return;
                }
                String delivery = Annotations.enumName(contract.get(), "delivery", "AT_LEAST_ONCE");
                if ("AT_MOST_ONCE".equals(delivery) || Annotations.has(item, Idempotent.class)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        ("%s consumes a stream with delivery=%s but is not annotated @Idempotent. "
                                        + "Name the field that identifies a logical delivery, and make the "
                                        + "handler safe to repeat.")
                                .formatted(item.getName(), delivery)));
            }
        };
    }

    private static ArchCondition<JavaClass> carryAnAdrForExpensiveGuarantees() {
        return new ArchCondition<>("justify expensive guarantees with an ADR") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaAnnotation<JavaClass>> contract = Annotations.find(item, EventContract.class);
                if (contract.isEmpty()) {
                    return;
                }
                List<String> reasons = new ArrayList<>();
                String retention = Annotations.enumName(contract.get(), "retention", "TIME_WINDOW");
                if ("INFINITE".equals(retention)) {
                    reasons.add("retention=INFINITE, so storage grows without bound and someone owns the replay story");
                }
                if ("GLOBAL".equals(Annotations.enumName(contract.get(), "ordering", "PER_KEY"))) {
                    reasons.add("ordering=GLOBAL, which forces a single partition and caps throughput at one consumer");
                }
                if (Annotations.bool(contract.get(), "containsPersonalData", false)
                        && COMPACTING_RETENTIONS.contains(retention)) {
                    reasons.add("personal data on a compacted stream, where a tombstone removes the current "
                            + "value but earlier copies survive in uncompacted segments");
                }
                if (reasons.isEmpty() || Annotations.has(item, com.acme.kernel.arch.Adr.class)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(
                        item,
                        "%s declares %s. Record the decision in docs/adr and reference it with @Adr."
                                .formatted(item.getName(), String.join("; and ", reasons))));
            }
        };
    }
}
