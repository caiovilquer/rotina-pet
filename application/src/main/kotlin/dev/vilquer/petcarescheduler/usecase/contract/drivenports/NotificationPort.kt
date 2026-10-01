package dev.vilquer.petcarescheduler.usecase.contract.drivenports

interface NotificationPort {
    /** @return true se a entrega foi bem-sucedida; o chamador decide se/quando tentar de novo. */
    fun sendEventReminder(target: EventReminderTarget): Boolean
    fun sendCareReminder(target: CareReminderNotificationTarget): Boolean = false
    fun sendCareEscalation(target: CareEscalationNotificationTarget): Boolean = false
}

data class CareEscalationNotificationTarget(
    val recipientEmail: String,
    val petName: String,
    val careTitle: String,
    val dueAt: java.time.Instant,
    val timezone: String = dev.vilquer.petcarescheduler.core.domain.household.HouseholdTimezone.DEFAULT_ID,
)
