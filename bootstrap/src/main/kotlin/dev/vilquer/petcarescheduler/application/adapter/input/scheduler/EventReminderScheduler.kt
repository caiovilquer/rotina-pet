package dev.vilquer.petcarescheduler.application.adapter.input.scheduler

import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CareScheduleMaintenanceUseCase
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CareScheduleScheduler(
    private val maintenance: CareScheduleMaintenanceUseCase
) {
    @Scheduled(cron = "0 */20 * * * *", zone = "\${app.timezone}")
    @SchedulerLock(name = "materializeCareSchedule", lockAtMostFor = "PT4M", lockAtLeastFor = "PT15S")
    fun maintainSchedule() = maintenance.materializeAndEnqueueReminders()
}
