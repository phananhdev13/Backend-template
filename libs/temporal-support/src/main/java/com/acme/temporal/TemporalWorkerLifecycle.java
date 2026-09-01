package com.acme.temporal;

import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.InboundAdapter;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.annotation.AnnotationUtils;

/**
 * Starts and stops this service's one Temporal worker alongside the Spring container - the
 * worker-bootstrap equivalent of what Boot's own autoconfiguration does for a
 * {@code @KafkaListener} container, hand-built because no such autoconfiguration exists for
 * plain {@code temporal-sdk}.
 *
 * <p>Registers every {@code @WorkflowDefinition} class {@link WorkflowDefinitions} finds by
 * scanning, and every Spring bean carrying {@code @InboundAdapter(AdapterKind.WORKFLOW)} as an
 * activity implementation - the same shape a {@code @KafkaListener} bean is discovered and wired
 * by Boot, applied to Temporal's own registration API instead.
 */
class TemporalWorkerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerLifecycle.class);

    private final WorkflowClient client;
    private final ApplicationContext context;
    private final TemporalProperties properties;

    private WorkerFactory factory;
    private volatile boolean running;

    TemporalWorkerLifecycle(WorkflowClient client, ApplicationContext context, TemporalProperties properties) {
        this.client = client;
        this.context = context;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (properties.getTaskQueue() == null) {
            log.info("No acme.temporal.task-queue configured; this service starts no Temporal worker.");
            return;
        }
        factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(properties.getTaskQueue());

        List<Class<?>> workflows = WorkflowDefinitions.scan(properties.getBasePackages());
        if (!workflows.isEmpty()) {
            worker.registerWorkflowImplementationTypes(workflows.toArray(new Class<?>[0]));
        }

        List<Object> activities = discoverActivityBeans();
        if (!activities.isEmpty()) {
            worker.registerActivitiesImplementations(activities.toArray());
        }

        factory.start();
        running = true;
        log.info(
                "Temporal worker started on task queue {} ({} workflow type(s), {} activity bean(s))",
                properties.getTaskQueue(),
                workflows.size(),
                activities.size());
    }

    @Override
    public void stop() {
        if (factory != null) {
            factory.shutdown();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * A default higher than every other {@code SmartLifecycle} phase this platform declares, so
     * the worker starts last - after whatever it depends on (a DataSource, a cache, a gRPC
     * channel) is already up - and stops first.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private List<Object> discoverActivityBeans() {
        List<Object> activities = new ArrayList<>();
        for (Map.Entry<String, Object> entry :
                context.getBeansWithAnnotation(InboundAdapter.class).entrySet()) {
            Object bean = entry.getValue();
            InboundAdapter adapter = AnnotationUtils.findAnnotation(bean.getClass(), InboundAdapter.class);
            if (adapter != null && adapter.value() == AdapterKind.WORKFLOW) {
                activities.add(bean);
            }
        }
        return activities;
    }
}
