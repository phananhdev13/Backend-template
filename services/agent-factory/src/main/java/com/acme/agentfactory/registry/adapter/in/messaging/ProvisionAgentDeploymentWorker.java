package com.acme.agentfactory.registry.adapter.in.messaging;

import com.acme.agentfactory.registry.application.port.in.ProvisionAgentDeploymentCommand;
import com.acme.agentfactory.registry.application.port.in.ProvisionAgentDeploymentUseCase;
import com.acme.agentfactory.registry.domain.AgentId;
import com.acme.agentfactory.registry.domain.ProvisionAgentDeploymentTask;
import com.acme.agentfactory.registry.domain.VersionNumber;
import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.InboundAdapter;
import com.acme.kernel.event.Idempotent;
import com.acme.kernel.task.TaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that a deployment was provisioned for an activated agent version.
 *
 * <p>This is the platform's first real classic-queue consumer, the RabbitMQ pair to
 * {@code AgentActivationAuditListener}'s Kafka one: {@code messaging-support} supplies the queue,
 * the Jackson-3 message converter and the retry-then-dead-letter policy, and this class is the one
 * place that puts a real {@code @RabbitListener} method behind the declared {@code @TaskHandler}.
 * See the {@code task-queues} skill for why {@code @TaskHandler} alone - with no listener method -
 * does nothing at all.
 *
 * <p>Unlike the Kafka consumer, there is no consumer group to declare: every worker bound to
 * {@code agents.provision-deployment} competes for the same messages, so scaling this worker out
 * is starting another instance, not a topology decision made in code.
 */
@TaskHandler(handles = ProvisionAgentDeploymentTask.class)
@Idempotent(
        key = "agentId",
        note = "The deployment row is an upsert keyed by (agentId, version); a redelivered task is a no-op")
@InboundAdapter(AdapterKind.MESSAGING)
public class ProvisionAgentDeploymentWorker {

    private static final Logger log = LoggerFactory.getLogger(ProvisionAgentDeploymentWorker.class);

    private final ProvisionAgentDeploymentUseCase provisionDeployment;

    public ProvisionAgentDeploymentWorker(ProvisionAgentDeploymentUseCase provisionDeployment) {
        this.provisionDeployment = provisionDeployment;
    }

    @RabbitListener(queues = "agents.provision-deployment")
    @Transactional
    public void on(ProvisionAgentDeploymentTask task) {
        provisionDeployment.provisionDeployment(new ProvisionAgentDeploymentCommand(
                new AgentId(task.agentId()), new VersionNumber(task.version()), task.submittedAt()));
        log.info("Provisioned deployment agentId={} version={}", task.agentId(), task.version());
    }
}
