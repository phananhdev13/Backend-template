package com.acme.temporal;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where the Temporal server is, and which workflows and activities this service registers. */
@ConfigurationProperties("acme.temporal")
public class TemporalProperties {

    /** {@code host:port} of the Temporal frontend service, e.g. {@code localhost:7233}. */
    private String target = "localhost:7233";

    private String namespace = "default";

    /**
     * The one task queue this service's worker polls. A service with more than one
     * genuinely independent workflow family is the signal to split it into another
     * service, the same way a second aggregate root is - not a reason to add a second
     * queue here.
     */
    private String taskQueue;

    /** Packages scanned for {@code @WorkflowDefinition} classes, mirroring every other
     * contract scanner in this platform. */
    private List<String> basePackages = new ArrayList<>();

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getTaskQueue() {
        return taskQueue;
    }

    public void setTaskQueue(String taskQueue) {
        this.taskQueue = taskQueue;
    }

    public List<String> getBasePackages() {
        return basePackages;
    }

    public void setBasePackages(List<String> basePackages) {
        this.basePackages = basePackages;
    }
}
