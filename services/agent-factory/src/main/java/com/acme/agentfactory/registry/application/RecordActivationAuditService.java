package com.acme.agentfactory.registry.application;

import com.acme.agentfactory.registry.application.port.in.RecordActivationAuditCommand;
import com.acme.agentfactory.registry.application.port.in.RecordActivationAuditUseCase;
import com.acme.agentfactory.registry.application.port.out.ActivationAuditPort;
import com.acme.kernel.arch.UseCase;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes one row of the activation audit trail.
 *
 * <p>Small, but a use case rather than three lines inside the Kafka listener: the audit trail is
 * an application operation, and leaving it in the adapter put a transaction boundary and a SQL
 * statement in a class whose job is to translate a message. The listener still brackets this call
 * in its own transaction so the delivery mark and this write commit together - see P-071.
 */
@UseCase(id = "UC-AGT-004", value = "The platform records that an agent version became active")
public class RecordActivationAuditService implements RecordActivationAuditUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordActivationAuditService.class);

    private final ActivationAuditPort audit;

    public RecordActivationAuditService(ActivationAuditPort audit) {
        this.audit = audit;
    }

    @Override
    @Observed(name = "usecase.record-activation-audit", contextualName = "UC-AGT-004")
    public void recordActivationAudit(RecordActivationAuditCommand command) {
        audit.append(command.agentId(), command.version(), command.activatedAt());
        log.info(
                "UC-AGT-004 audited activation agentId={} version={}",
                command.agentId().value(),
                command.version().value());
    }
}
