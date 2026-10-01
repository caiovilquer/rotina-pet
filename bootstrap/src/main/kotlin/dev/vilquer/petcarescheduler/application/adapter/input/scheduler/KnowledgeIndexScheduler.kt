package dev.vilquer.petcarescheduler.application.adapter.input.scheduler

import dev.vilquer.petcarescheduler.usecase.contract.drivingports.KnowledgeIndexUseCase
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class KnowledgeIndexScheduler(private val knowledge: KnowledgeIndexUseCase) {
    @Scheduled(fixedDelayString = "\${app.ai.index-delay-ms:10000}")
    @SchedulerLock(name = "processKnowledgeIndex", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
    fun process() = knowledge.processBatch()
}
