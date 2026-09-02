package com.acme.agentfactory.registry.application.port.in;

import com.acme.kernel.arch.InputPort;

/** Records an activation in the audit trail. */
@InputPort
public interface RecordActivationAuditUseCase {

    void recordActivationAudit(RecordActivationAuditCommand command);
}
