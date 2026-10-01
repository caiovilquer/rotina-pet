package dev.vilquer.petcarescheduler.application.adapter.input.scheduler

import dev.vilquer.petcarescheduler.usecase.contract.drivingports.DispatchPendingCareRemindersUseCase
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CareReminderRelayScheduler(private val relay: DispatchPendingCareRemindersUseCase) {
    @Scheduled(cron = "0 */20 * * * *", zone = "\${app.timezone}")
    @SchedulerLock(name = "dispatchPendingCareReminders", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    fun dispatch() = relay.dispatchPendingCareReminders()
}
