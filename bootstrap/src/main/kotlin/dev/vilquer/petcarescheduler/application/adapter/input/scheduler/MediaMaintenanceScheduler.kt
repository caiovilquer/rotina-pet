package dev.vilquer.petcarescheduler.application.adapter.input.scheduler

import dev.vilquer.petcarescheduler.usecase.contract.drivingports.MediaMaintenanceUseCase
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class MediaMaintenanceScheduler(private val media: MediaMaintenanceUseCase) {
    @Scheduled(cron = "0 */20 * * * *", zone = "\${app.timezone}")
    @SchedulerLock(name = "cleanupMediaAssets", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    fun cleanupMedia() = media.cleanupMedia()
}
