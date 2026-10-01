package dev.vilquer.petcarescheduler.application.adapter.input.scheduler

import dev.vilquer.petcarescheduler.usecase.contract.drivingports.DispatchPendingRemindersUseCase
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ReminderRelayScheduler(
    private val dispatchPendingReminders: DispatchPendingRemindersUseCase
) {
    /**
     * Roda a cada 20 minutos, alinhado às demais tarefas frequentes para abrir
     * uma janela de inatividade maior que os 10 minutos exigidos pelo Railway.
     * Durante a hibernação, os lembretes aguardam a aplicação voltar a funcionar.
     */
    @Scheduled(cron = "0 */20 * * * *", zone = "\${app.timezone}")
    @SchedulerLock(name = "dispatchPendingReminders", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    fun dispatchPendingReminders() {
        dispatchPendingReminders.dispatchPendingReminders()
    }
}
