package com.acme.agentfactory.registry.adapter.out.messaging;

import com.acme.agentfactory.registry.domain.AgentVersionActivated;
import com.acme.agentfactory.registry.domain.ProvisionAgentDeploymentTask;
import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.ImplementsPrinciple;
import com.acme.kernel.arch.OutboundAdapter;
import com.acme.messaging.TaskPublisher;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * Submits a deployment-provisioning job once an activation has actually committed.
 *
 * <p>Same shape as {@link AgentEventPublisherAdapter}, and for the same reason: the use case never
 * calls {@link TaskPublisher} directly - {@code OutboxRules.noBrokerCallInsideATransaction} would
 * refuse a {@code @UseCase} depending on {@code com.acme.messaging}, and the refusal is protecting
 * something real here too. A task submitted inside the transaction that activated the version
 * would still be submitted if that transaction then rolled back; reacting to the same committed
 * event this adapter already forwards to Kafka is what keeps "provision a deployment" and "the
 * activation actually happened" from disagreeing.
 */
@OutboundAdapter(port = TaskPublisher.class, kind = AdapterKind.MESSAGING)
@ImplementsPrinciple(
        value = {"P-051", "P-072", "P-131"},
        note = "Producer delivery timeout and retries are set in application.yml; reacts to the same "
                + "committed AgentVersionActivated as AgentEventPublisherAdapter, so a rolled-back "
                + "activation never submits a provisioning job for it.")
public class AgentDeploymentProvisioningAdapter {

    private static final Logger log = LoggerFactory.getLogger(AgentDeploymentProvisioningAdapter.class);

    private final TaskPublisher tasks;
    private final Clock clock;

    AgentDeploymentProvisioningAdapter(TaskPublisher tasks, Clock clock) {
        this.tasks = tasks;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(AgentVersionActivated event) {
        tasks.submit(new ProvisionAgentDeploymentTask(event.agentId(), event.version(), Instant.now(clock)));
        log.debug("Submitted deployment provisioning job agentId={} version={}", event.agentId(), event.version());
    }
}
