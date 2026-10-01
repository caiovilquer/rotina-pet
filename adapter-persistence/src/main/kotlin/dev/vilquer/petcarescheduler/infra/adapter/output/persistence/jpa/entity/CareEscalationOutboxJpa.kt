package dev.vilquer.petcarescheduler.infra.adapter.output.persistence.jpa.entity

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "care_escalation_outbox")
class CareEscalationOutboxJpa {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
    @Column(name = "occurrence_id", nullable = false) lateinit var occurrenceId: UUID
    @Column(name = "household_id", nullable = false) lateinit var householdId: UUID
    @Column(name = "recipient_tutor_id", nullable = false) var recipientTutorId: Long = 0
    @Column(name = "recipient_email", nullable = false) lateinit var recipientEmail: String
    @Column(name = "pet_name", nullable = false) lateinit var petName: String
    @Column(name = "care_title", nullable = false) lateinit var careTitle: String
    @Column(name = "due_at_instant", nullable = false) lateinit var dueAt: Instant
    @Column(name = "created_at", nullable = false) lateinit var createdAt: Instant
    @Column(name = "sent_at") var sentAt: Instant? = null
    @Column(name = "cancelled_at") var cancelledAt: Instant? = null
    @Column(nullable = false) var attempts: Int = 0
}
