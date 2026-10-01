package dev.vilquer.petcarescheduler.application.adapter.input.scheduler

import dev.vilquer.petcarescheduler.usecase.contract.drivingports.WhatsAppInboundUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.WhatsAppOutboxUseCase
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class WhatsAppInboxScheduler(
    private val whatsapp: WhatsAppInboundUseCase,
    private val meters: MeterRegistry,
) {
    @Scheduled(fixedDelayString = "\${app.whatsapp.inbox-delay-ms:1000}")
    @SchedulerLock(name = "processWhatsAppInbox", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0.1S")
    fun process() {
        val count = whatsapp.processBatch()
        if (count > 0) meters.counter("rotinapet.whatsapp.inbox.processed").increment(count.toDouble())
    }
}

@Component
class WhatsAppOutboxScheduler(
    private val whatsapp: WhatsAppOutboxUseCase,
    private val meters: MeterRegistry,
) {
    @Scheduled(fixedDelayString = "\${app.whatsapp.outbox-delay-ms:1000}")
    @SchedulerLock(name = "relayWhatsAppOutbox", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0.1S")
    fun process() {
        val count = whatsapp.relayBatch()
        if (count > 0) meters.counter("rotinapet.whatsapp.outbox.processed").increment(count.toDouble())
    }
}
