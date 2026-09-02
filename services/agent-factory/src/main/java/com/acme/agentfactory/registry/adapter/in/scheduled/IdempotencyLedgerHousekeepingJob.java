package com.acme.agentfactory.registry.adapter.in.scheduled;

import com.acme.kernel.arch.AdapterKind;
import com.acme.kernel.arch.InboundAdapter;
import com.acme.messaging.ProcessedMessageStore;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sweeps expired rows from the idempotency ledger {@code messaging-support} keeps for every
 * {@code @Idempotent} handler in this service -
 * {@link com.acme.agentfactory.registry.adapter.in.messaging.AgentActivationAuditListener} and
 * {@link com.acme.agentfactory.registry.adapter.in.messaging.ProvisionAgentDeploymentWorker}.
 *
 * <p>{@link ProcessedMessageStore#purgeExpired()} has existed since the ledger itself was built,
 * deliberately separated from {@code markProcessed} so the sweep's cost never lands on a message
 * handler's latency - but nothing called it until this job existed. A ledger nobody sweeps still
 * works, right up until it is the largest table in the database.
 *
 * <p>Cluster-safe via ShedLock, not Quartz (P-132): this is exactly the shape ShedLock is for -
 * a short, idempotent maintenance job where "at most one instance runs this tick, and the worst
 * case if a node dies mid-sweep is a delayed retry" is a fine guarantee. A batch job that must
 * survive a crash mid-run without waiting out {@code lockAtMostFor} would reach for Quartz's
 * clustered {@code JobStore} instead.
 */
@InboundAdapter(AdapterKind.SCHEDULER)
public class IdempotencyLedgerHousekeepingJob {

    private final ProcessedMessageStore processed;

    public IdempotencyLedgerHousekeepingJob(ProcessedMessageStore processed) {
        this.processed = processed;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "idempotency-ledger-housekeeping", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void purgeExpiredDeliveries() {
        processed.purgeExpired();
    }
}
