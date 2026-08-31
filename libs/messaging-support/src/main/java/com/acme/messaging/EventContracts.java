package com.acme.messaging;

import com.acme.kernel.event.DomainEvent;
import com.acme.kernel.event.EventContract;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads {@link EventContract} declarations, once per event type.
 *
 * <p>Reflection on the publish path is a cost paid per message unless someone caches it, so this
 * caches it. The cache is keyed by class and never invalidated, which is correct because an
 * annotation cannot change after the class is loaded.
 *
 * <p>This is also the only place that knows how to get a partition key value out of an event. That
 * matters more than it looks: the key decides which partition a message lands on, and therefore
 * whether the per-key ordering the contract promises actually holds. Resolving it in one place,
 * from the declared component name, is what stops a call site from quietly keying by something
 * else.
 */
public final class EventContracts {

    private static final Map<Class<?>, StreamDescriptor> DESCRIPTORS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> KEY_ACCESSORS = new ConcurrentHashMap<>();

    private EventContracts() {}

    /**
     * Parses the contract declared on an event type.
     *
     * @param eventType the annotated event type
     * @return its parsed contract
     * @throws IllegalArgumentException if the type carries no {@link EventContract}; an event
     *     with no declared key, retention or delivery guarantee leaves those decisions to whoever
     *     creates the topic, which is the failure this whole module exists to prevent
     */
    public static StreamDescriptor describe(Class<? extends DomainEvent> eventType) {
        StreamDescriptor cached = DESCRIPTORS.get(eventType);
        if (cached != null) {
            return cached;
        }
        EventContract contract = eventType.getAnnotation(EventContract.class);
        if (contract == null) {
            throw new IllegalArgumentException(("%s carries no @EventContract, so this module has "
                            + "nothing to provision or publish from. Annotate it with the stream name, "
                            + "partition key, retention and delivery guarantee the stream promises. "
                            + "See docs/principles/P-070-event-semantics.md")
                    .formatted(eventType.getName()));
        }
        StreamDescriptor descriptor = new StreamDescriptor(
                contract.stream(),
                contract.version(),
                contract.partitionKey(),
                contract.payload(),
                contract.retention(),
                contract.retentionDays(),
                contract.delivery(),
                contract.ordering(),
                contract.schema(),
                contract.containsPersonalData(),
                eventType);
        DESCRIPTORS.put(eventType, descriptor);
        return descriptor;
    }

    /**
     * Reads the value of the component the contract nominated as the partition key.
     *
     * <p>Never returns null or blank. A null key is not a harmless edge case: Kafka partitions a
     * null-keyed record round-robin, so one absent identifier silently converts a per-key ordering
     * promise into no ordering at all, and the consumer that depended on it fails somewhere else
     * entirely. Failing at the publish call is the only place the cause is still visible.
     *
     * @param event the event about to be published
     * @return the partition key value, as a string
     * @throws IllegalArgumentException if the event type declares no contract
     * @throws IllegalStateException if the declared component is missing, unreadable, or empty
     */
    public static String partitionKeyValueOf(DomainEvent event) {
        Class<? extends DomainEvent> type = event.getClass().asSubclass(DomainEvent.class);
        StreamDescriptor descriptor = describe(type);
        Method accessor = KEY_ACCESSORS.computeIfAbsent(type, key -> resolveAccessor(descriptor));
        Object value = read(accessor, event, descriptor);
        if (value == null) {
            throw new IllegalStateException(("%s declares partitionKey \"%s\", but that component is null on "
                            + "this instance. A null key is partitioned round-robin, which drops the "
                            + "ordering the contract promises without any error. Make the component "
                            + "non-null at construction, or declare a component that always has a value.")
                    .formatted(descriptor.eventType().getName(), descriptor.partitionKey()));
        }
        String key = String.valueOf(value);
        if (key.isBlank()) {
            throw new IllegalStateException(("%s declares partitionKey \"%s\", but its value is blank on this "
                            + "instance. A blank key groups every such message onto one partition and "
                            + "makes them indistinguishable on a compacted stream.")
                    .formatted(descriptor.eventType().getName(), descriptor.partitionKey()));
        }
        return key;
    }

    private static Method resolveAccessor(StreamDescriptor descriptor) {
        Class<? extends DomainEvent> type = descriptor.eventType();
        String name = descriptor.partitionKey();
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            // Records give every component a public accessor, so reaching here means either a
            // non-record event or a key naming something that does not exist. Distinguish the two
            // before reporting, because the fix differs.
            return fallbackAccessor(type, name);
        }
    }

    private static Method fallbackAccessor(Class<? extends DomainEvent> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                throw new IllegalStateException(("%s declares partitionKey \"%s\", which exists as a field but "
                                + "has no public accessor. Events are records in the domain layer; make "
                                + "this one a record, or add a public %s() accessor.")
                        .formatted(type.getName(), name, name));
            }
        }
        throw new IllegalStateException(("%s declares partitionKey \"%s\", which is not a component of the "
                        + "event. Rename the key to an existing component, or add the component. "
                        + "EventContractRules catches this at build time - if it did not here, the "
                        + "event is outside the packages that test scans.")
                .formatted(type.getName(), name));
    }

    private static Object read(Method accessor, DomainEvent event, StreamDescriptor descriptor) {
        try {
            return accessor.invoke(event);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw new IllegalStateException(
                    "Could not read partitionKey \"%s\" from %s."
                            .formatted(
                                    descriptor.partitionKey(),
                                    descriptor.eventType().getName()),
                    ex);
        }
    }
}
