package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "care_reminder_outbox")
class CareReminderOutboxJpa {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
    @Column(name = "occurrence_id", nullable = false) lateinit var occurrenceId: UUID
    @Column(name = "tutor_id", nullable = false) var tutorId: Long = 0
    @Column(name = "tutor_email", nullable = false) lateinit var tutorEmail: String
    @Column(name = "pet_name") var petName: String? = null
    @Column(name = "created_at", nullable = false) lateinit var createdAt: Instant
    @Column(name = "sent_at") var sentAt: Instant? = null
    @Column(name = "cancelled_at") var cancelledAt: Instant? = null
    @Column(nullable = false) var attempts: Int = 0
}
