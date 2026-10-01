package dev.vilquer.petcarescheduler.application

import dev.vilquer.petcarescheduler.core.domain.entity.*
import dev.vilquer.petcarescheduler.core.domain.care.*
import dev.vilquer.petcarescheduler.core.domain.reset.PasswordResetToken
import dev.vilquer.petcarescheduler.core.domain.session.RefreshToken
import dev.vilquer.petcarescheduler.core.domain.media.MediaAsset
import dev.vilquer.petcarescheduler.core.domain.media.MediaStatus
import dev.vilquer.petcarescheduler.core.domain.health.*
import dev.vilquer.petcarescheduler.core.domain.household.HouseholdId
import dev.vilquer.petcarescheduler.core.domain.household.*
import dev.vilquer.petcarescheduler.core.domain.valueobject.Email
import dev.vilquer.petcarescheduler.usecase.command.PlaceCategory
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.*
import dev.vilquer.petcarescheduler.usecase.result.GeoLocation
import dev.vilquer.petcarescheduler.usecase.result.PlaceDetails
import dev.vilquer.petcarescheduler.usecase.result.PlacePhoto
import dev.vilquer.petcarescheduler.usecase.result.PlaceReview
import dev.vilquer.petcarescheduler.usecase.result.PlaceSummary
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID

internal val TEST_HOUSEHOLD_ID = HouseholdId(UUID.fromString("00000000-0000-0000-0000-000000000001"))

internal class FakeClock(var fixed: ZonedDateTime) : ClockPort {
    override fun now(): ZonedDateTime = fixed
}

internal class FakeNotifier : NotificationPort {
    val notified = mutableListOf<Event>()
    val notifiedCare = mutableListOf<CareReminderNotificationTarget>()
    var deliverySucceeds = true
    override fun sendEventReminder(target: EventReminderTarget): Boolean {
        notified.add(target.event)
        return deliverySucceeds
    }
    override fun sendCareReminder(target: CareReminderNotificationTarget): Boolean {
        notifiedCare += target
        return deliverySucceeds
    }
}

internal class FakeReminderOutboxPort : ReminderOutboxPort {
    private val store = LinkedHashMap<Long, ReminderOutboxMessage>()
    private val sentIds = mutableSetOf<Long>()
    private var seq = 1L
    override fun enqueueIfAbsent(message: ReminderOutboxMessage) {
        if (store.values.any { it.eventId == message.eventId }) return
        val id = seq++
        store[id] = message.copy(id = id)
    }
    override fun findPendingDelivery(maxAttempts: Int, limit: Int): List<ReminderOutboxMessage> =
        store.values.filter { it.id !in sentIds && it.attempts < maxAttempts }
            .sortedBy { it.createdAt }
            .take(limit)
    override fun markSent(id: Long) { sentIds.add(id) }
    override fun incrementAttempts(id: Long) {
        store[id]?.let { store[id] = it.copy(attempts = it.attempts + 1) }
    }
    override fun resetForEvent(eventId: EventId) {
        store.entries.removeIf { it.value.eventId == eventId }
    }
    fun allMessages(): Collection<ReminderOutboxMessage> = store.values
    fun isSent(id: Long?): Boolean = id != null && id in sentIds
}

internal class FakePasswordHash : PasswordHashPort {
    override fun hash(raw: CharSequence): String = "hashed-$raw"
    override fun matches(raw: CharSequence, hash: String): Boolean = hash(raw) == hash
}

/** Executa o bloco diretamente, sem transação real — suficiente para testar orquestração. */
internal class FakeTransactionPort : TransactionPort {
    override fun <T> execute(block: () -> T): T = block()
}

internal class InMemoryMediaAssetRepo : MediaAssetRepositoryPort {
    private val store = LinkedHashMap<UUID, MediaAsset>()
    override fun save(asset: MediaAsset): MediaAsset = asset.also { store[it.id] = it }
    override fun findById(id: UUID): MediaAsset? = store[id]
    override fun findByIdForUpdate(id: UUID): MediaAsset? = store[id]
    override fun delete(id: UUID) { store.remove(id) }
    override fun findCleanupCandidates(pendingBefore: Instant, limit: Int): List<MediaAsset> =
        store.values.filter {
            it.status == MediaStatus.PENDING_DELETE ||
                (it.status == MediaStatus.PENDING && it.createdAt.isBefore(pendingBefore))
        }.take(limit)
    override fun markPetAssetsForDeletion(petId: PetId) = Unit
    override fun markTutorAssetsForDeletion(tutorId: TutorId) = Unit
    fun all(): Collection<MediaAsset> = store.values
}

internal class FakeObjectStorage : ObjectStoragePort {
    val objects = LinkedHashMap<String, ByteArray>()
    val deleted = mutableListOf<String>()
    var failDeletes = false
    var lastDownloadFilename: String? = null
    override fun presignUpload(key: String, contentType: String, checksumSha256: String, expiresIn: Duration) =
        PresignedUpload("https://storage.example/$key?signature=test", mapOf("content-type" to contentType))
    override fun readObject(key: String, maxBytes: Long): ByteArray =
        objects[key]?.takeIf { it.size <= maxBytes } ?: error("object_not_found")
    override fun promote(sourceKey: String, destinationKey: String, contentType: String) {
        objects[destinationKey] = objects.remove(sourceKey) ?: error("object_not_found")
    }
    override fun delete(key: String) {
        if (failDeletes) error("storage_unavailable")
        objects.remove(key)
        deleted += key
    }
    override fun presignDownload(key: String, expiresIn: Duration, downloadFilename: String?): String {
        lastDownloadFilename = downloadFilename
        return "https://storage.example/$key?signature=download"
    }
}

internal class InMemoryHealthRecordRepo : HealthRecordRepositoryPort {
    private val store = LinkedHashMap<HealthRecordId, HealthRecord>()
    override fun save(record: HealthRecord): HealthRecord {
        val saved = record.copy(version = (store[record.id]?.version ?: -1L) + 1L)
        store[saved.id] = saved
        return saved
    }
    override fun findByIdAndTutor(id: HealthRecordId, tutorId: TutorId) = store[id]?.takeIf { it.tutorId == tutorId }
    override fun findByIdAndTutorForUpdate(id: HealthRecordId, tutorId: TutorId) = findByIdAndTutor(id, tutorId)
    override fun search(tutorId: TutorId, filter: HealthRecordFilter, page: Int, size: Int) = store.values
        .filter { it.tutorId == tutorId && it.petId == filter.petId && it.occurredAt >= filter.from && it.occurredAt < filter.to }
        .filter { filter.type == null || it.type == filter.type }
        .sortedByDescending { it.occurredAt }
        .drop(page * size).take(size)
    override fun count(tutorId: TutorId, filter: HealthRecordFilter) =
        search(tutorId, filter, 0, Int.MAX_VALUE).size.toLong()
    override fun delete(id: HealthRecordId) { store.remove(id) }
    override fun findByIdAndHousehold(id: HealthRecordId, householdId: HouseholdId) = store[id]?.takeIf { it.householdId == householdId }
    override fun findByIdAndHouseholdForUpdate(id: HealthRecordId, householdId: HouseholdId) = findByIdAndHousehold(id, householdId)
    override fun searchByHousehold(householdId: HouseholdId, filter: HealthRecordFilter, page: Int, size: Int) = store.values
        .filter { it.householdId == householdId && it.petId == filter.petId && it.occurredAt >= filter.from && it.occurredAt < filter.to }
        .filter { filter.type == null || it.type == filter.type }.sortedByDescending { it.occurredAt }.drop(page * size).take(size)
    override fun countByHousehold(householdId: HouseholdId, filter: HealthRecordFilter) = searchByHousehold(householdId, filter, 0, Int.MAX_VALUE).size.toLong()
    override fun searchCostsByHousehold(householdId: HouseholdId, petId: PetId?, from: Instant, to: Instant, limit: Int) = store.values
        .filter { it.householdId == householdId && (petId == null || it.petId == petId) && it.occurredAt >= from && it.occurredAt < to && it.costAmount != null }
        .sortedByDescending { it.occurredAt }.take(limit)
}

internal class InMemoryHealthAttachmentRepo : HealthRecordAttachmentRepositoryPort {
    private val store = LinkedHashMap<UUID, HealthRecordAttachment>()
    override fun save(attachment: HealthRecordAttachment) = attachment.also { store[it.mediaAssetId] = it }
    override fun countByRecord(recordId: HealthRecordId) = store.values.count { it.healthRecordId == recordId }.toLong()
    override fun listByRecordIds(recordIds: Collection<HealthRecordId>) = emptyMap<HealthRecordId, List<HealthAttachmentDetails>>()
    override fun findByMediaId(mediaId: UUID) = store[mediaId]
    override fun deleteByMediaId(mediaId: UUID) { store.remove(mediaId) }
}

internal class InMemoryHealthMeasurementRepo : HealthMeasurementRepositoryPort {
    private val store = LinkedHashMap<HealthMeasurementId, HealthMeasurement>()
    override fun save(measurement: HealthMeasurement): HealthMeasurement {
        val saved = measurement.copy(version = (store[measurement.id]?.version ?: -1L) + 1L)
        store[saved.id] = saved
        return saved
    }
    override fun findByIdAndTutor(id: HealthMeasurementId, tutorId: TutorId) = store[id]?.takeIf { it.tutorId == tutorId }
    override fun findByIdAndTutorForUpdate(id: HealthMeasurementId, tutorId: TutorId) = findByIdAndTutor(id, tutorId)
    override fun list(
        tutorId: TutorId, petId: PetId, type: HealthMeasurementType?, from: Instant, to: Instant, limit: Int,
    ) = store.values.filter {
        it.tutorId == tutorId && it.petId == petId && it.measuredAt >= from && it.measuredAt < to &&
            (type == null || it.type == type)
    }.sortedBy { it.measuredAt }.take(limit)
    override fun delete(id: HealthMeasurementId) { store.remove(id) }
    override fun findByIdAndHouseholdForUpdate(id: HealthMeasurementId, householdId: HouseholdId) = store[id]?.takeIf { it.householdId == householdId }
    override fun listByHousehold(householdId: HouseholdId, petId: PetId, type: HealthMeasurementType?, from: Instant, to: Instant, limit: Int) = store.values.filter {
        it.householdId == householdId && it.petId == petId && it.measuredAt >= from && it.measuredAt < to && (type == null || it.type == type)
    }.sortedBy { it.measuredAt }.take(limit)
}

internal class InMemoryPasswordResetTokenPort : PasswordResetTokenPort {
    private val store = LinkedHashMap<UUID, PasswordResetToken>()
    override fun create(token: PasswordResetToken): PasswordResetToken {
        store[token.id] = token
        return token
    }
    override fun findActiveByHash(tokenHash: String): PasswordResetToken? =
        store.values.firstOrNull { it.tokenHash == tokenHash && it.usedAt == null }
    override fun markUsed(id: UUID, usedAt: Instant) {
        store[id]?.let { store[id] = it.copy(usedAt = usedAt) }
    }
    override fun invalidateAllForUser(userId: TutorId, usedAt: Instant) {
        store.values.filter { it.userId == userId && it.usedAt == null }
            .forEach { store[it.id] = it.copy(usedAt = usedAt) }
    }
    override fun cleanup(now: Instant) {
        store.values.filter { it.expiresAt.isBefore(now) }.forEach { store.remove(it.id) }
    }
    fun allTokens(): Collection<PasswordResetToken> = store.values
}

internal class InMemoryRefreshTokenPort : RefreshTokenPort {
    private val store = LinkedHashMap<UUID, RefreshToken>()
    override fun create(token: RefreshToken): RefreshToken {
        store[token.id] = token
        return token
    }
    override fun findByHash(tokenHash: String): RefreshToken? =
        store.values.firstOrNull { it.tokenHash == tokenHash }
    override fun markRotated(id: UUID, replacedBy: UUID, at: Instant) {
        store[id]?.let { store[id] = it.copy(usedAt = at, replacedBy = replacedBy) }
    }
    override fun revokeFamily(familyId: UUID, at: Instant) {
        store.values.filter { it.familyId == familyId && it.revokedAt == null }
            .forEach { store[it.id] = it.copy(revokedAt = at) }
    }
    override fun revokeAllForUser(userId: TutorId, at: Instant) {
        store.values.filter { it.userId == userId && it.revokedAt == null }
            .forEach { store[it.id] = it.copy(revokedAt = at) }
    }
    override fun cleanup(now: Instant) {
        store.values.filter { it.expiresAt.isBefore(now) }.forEach { store.remove(it.id) }
    }
    fun allTokens(): Collection<RefreshToken> = store.values
}

internal class FakePasswordResetNotifier : PasswordResetNotifierPort {
    data class SentLink(val to: Email, val tokenPlain: String, val ttl: Duration, val returnUrl: String?)
    val sent = mutableListOf<SentLink>()
    override fun sendResetLink(to: Email, tokenPlain: String, ttl: Duration, returnUrl: String?) {
        sent.add(SentLink(to, tokenPlain, ttl, returnUrl))
    }
}

internal class InMemoryTutorRepo(initial: Map<TutorId, Tutor> = emptyMap()) : TutorRepositoryPort {
    private val store = LinkedHashMap<TutorId, Tutor>().apply { putAll(initial) }
    private var seq = (store.keys.map { it.value }.maxOrNull() ?: 0L) + 1
    override fun save(tutor: Tutor): Tutor {
        val id = tutor.id ?: TutorId(seq++)
        val saved = tutor.copy(id = id)
        store[id] = saved
        return saved
    }
    override fun findById(id: TutorId): Tutor? = store[id]
    override fun findByEmail(email: dev.vilquer.petcarescheduler.core.domain.valueobject.Email): Tutor? =
        store.values.firstOrNull { it.email.value == email.value }
    override fun findAll(page: Int, size: Int): List<Tutor> =
        store.values.drop(page * size).take(size)
    override fun countAll(): Long = store.size.toLong()
    override fun delete(id: TutorId) { store.remove(id) }
    override fun updatePassword(id: TutorId, passwordHash: String) {
        val existing = store[id] ?: return
        store[id] = existing.copy(passwordHash = passwordHash)
    }
    override fun bumpPasswordChangedAt(id: TutorId, whenUtc: java.time.Instant) {
        val existing = store[id] ?: return
        store[id] = existing.copy(passwordChangedAt = whenUtc)
    }
    override fun findPasswordChangedAt(id: TutorId): java.time.Instant? =
        store[id]?.passwordChangedAt
}

internal class InMemoryPetRepo(initial: Map<PetId, Pet> = emptyMap()) : PetRepositoryPort {
    private val store = LinkedHashMap<PetId, Pet>().apply { putAll(initial) }
    private var seq = (store.keys.map { it.value }.maxOrNull() ?: 0L) + 1
    override fun save(pet: Pet): Pet {
        val id = pet.id ?: PetId(seq++)
        val saved = pet.copy(id = id)
        store[id] = saved
        return saved
    }
    override fun findById(id: PetId): Pet? = store[id]
    override fun delete(id: PetId) { store.remove(id) }
    override fun listByTutor(tutorId: TutorId, page: Int, size: Int): List<Pet> =
        store.values.filter { it.tutorId == tutorId }.drop(page * size).take(size)
    override fun countByTutor(tutorId: TutorId): Long =
        store.values.count { it.tutorId == tutorId }.toLong()
    override fun findByIdAndTutor(id: PetId, tutorId: TutorId): Pet? =
        store[id]?.takeIf { it.tutorId == tutorId }
    override fun existsForTutor(id: PetId, tutorId: TutorId): Boolean =
        store[id]?.tutorId == tutorId
    override fun findAllByTutor(tutorId: TutorId): List<Pet> =
        store.values.filter { it.tutorId == tutorId }
    override fun listByHousehold(householdId: HouseholdId, page: Int, size: Int) =
        store.values.filter { it.householdId == householdId || it.householdId == null }.drop(page * size).take(size)
    override fun countByHousehold(householdId: HouseholdId) = store.values.count { it.householdId == householdId || it.householdId == null }.toLong()
    override fun findByIdAndHousehold(id: PetId, householdId: HouseholdId) = store[id]?.takeIf { it.householdId == householdId || it.householdId == null }
    override fun existsForHousehold(id: PetId, householdId: HouseholdId) = findByIdAndHousehold(id, householdId) != null
}

internal class InMemoryEventRepo(
    initial: Map<EventId, Event> = emptyMap(),
    private val countsByTutor: Map<TutorId, Long> = emptyMap(),
) : EventRepositoryPort {
    private val store = LinkedHashMap<EventId, Event>().apply { putAll(initial) }
    private var seq = (store.keys.map { it.value }.maxOrNull() ?: 0L) + 1
    override fun save(event: Event): Event {
        val id = event.id ?: EventId(seq++)
        val saved = event.copy(id = id)
        store[id] = saved
        return saved
    }
    override fun findById(id: EventId): Event? = store[id]
    override fun findByPetId(petId: PetId): List<Event> =
        store.values.filter { it.petId == petId }
    override fun delete(id: EventId) {
        store.remove(id)
    }
    override fun listByTutor(tutorId: TutorId, page: Int, size: Int): List<Event> =
        emptyList()
    override fun countByTutor(tutorId: TutorId): Long = countsByTutor[tutorId] ?: 0
    override fun findUpcomingByTutor(
        tutorId: TutorId,
        start: java.time.LocalDateTime,
        end: java.time.LocalDateTime,
        limit: Int,
    ): List<Event> = store.values.filter {
        it.status == Status.PENDING && !it.dateStart.isBefore(start) && !it.dateStart.isAfter(end)
    }.sortedBy { it.dateStart }.take(limit)
    override fun findByIdAndTutor(id: EventId, tutorId: TutorId): Event? = store[id]
    override fun existsForTutor(id: EventId, tutorId: TutorId): Boolean = store.containsKey(id)
    override fun findPendingReminders(start: java.time.LocalDateTime, end: java.time.LocalDateTime):
        List<EventReminderTarget> =
        store.values.filter {
            it.status == Status.PENDING && !it.dateStart.isBefore(start) && it.dateStart.isBefore(end)
        }.map { EventReminderTarget(it, "test@example.com", null) }

    fun allEvents(): Collection<Event> = store.values
}

internal class InMemoryCareOccurrenceRepo(
    initial: Collection<CareOccurrence> = emptyList(),
) : CareOccurrenceRepositoryPort {
    private val store = LinkedHashMap<CareOccurrenceId, CareOccurrence>().apply {
        initial.forEach { put(it.id, it) }
    }

    override fun save(occurrence: CareOccurrence): CareOccurrence = occurrence.also { store[it.id] = it }
    override fun findById(id: CareOccurrenceId): CareOccurrence? = store[id]
    override fun findPlanIdByIdAndHousehold(id: CareOccurrenceId, householdId: HouseholdId) =
        store[id]?.takeIf { it.householdId == householdId }?.planId

    override fun saveAllIfAbsent(occurrences: List<CareOccurrence>): Int {
        var inserted = 0
        occurrences.forEach {
            if (store.putIfAbsent(it.id, it) == null) inserted++
        }
        return inserted
    }

    override fun findByIdAndTutor(id: CareOccurrenceId, tutorId: TutorId): CareOccurrence? =
        store[id]?.takeIf { it.tutorId == tutorId }

    override fun findByIdAndTutorForUpdate(id: CareOccurrenceId, tutorId: TutorId): CareOccurrence? =
        findByIdAndTutor(id, tutorId)

    override fun search(
        tutorId: TutorId,
        filter: CareOccurrenceFilter,
        page: Int,
        size: Int,
    ): List<CareOccurrence> = filtered(tutorId, filter).drop(page * size).take(size)

    override fun count(tutorId: TutorId, filter: CareOccurrenceFilter): Long =
        filtered(tutorId, filter).size.toLong()

    override fun cancelScheduledFrom(planId: CarePlanId, from: Instant, updatedAt: Instant): Int =
        cancelMatching(updatedAt) { it.planId == planId && !it.dueAt.isBefore(from) }

    override fun cancelAllScheduled(planId: CarePlanId, updatedAt: Instant): Int =
        cancelMatching(updatedAt) { it.planId == planId }

    override fun findReminderCandidates(
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<CareOccurrence> = store.values.asSequence()
        .filter { it.status == CareOccurrenceStatus.SCHEDULED && !it.dueAt.isBefore(from) && !it.dueAt.isAfter(to) }
        .sortedBy { it.dueAt }
        .take(limit)
        .toList()

    override fun findScheduledFrom(planId: CarePlanId, from: Instant): List<CareOccurrence> =
        store.values.filter { it.planId == planId && it.status == CareOccurrenceStatus.SCHEDULED && !it.dueAt.isBefore(from) }

    override fun countByTutor(tutorId: TutorId): Long = store.values.count { it.tutorId == tutorId }.toLong()

    override fun findUpcoming(
        tutorId: TutorId,
        from: Instant,
        to: Instant,
        limit: Int,
    ): List<CareOccurrence> = store.values.asSequence()
        .filter {
            it.tutorId == tutorId && it.status == CareOccurrenceStatus.SCHEDULED &&
                !it.dueAt.isBefore(from) && !it.dueAt.isAfter(to)
        }
        .sortedBy { it.dueAt }
        .take(limit)
        .toList()
    override fun findByIdAndHousehold(id: CareOccurrenceId, householdId: HouseholdId) = store[id]?.takeIf { it.householdId == householdId }
    override fun findByIdAndHouseholdForUpdate(id: CareOccurrenceId, householdId: HouseholdId) = findByIdAndHousehold(id, householdId)
    override fun searchByHousehold(householdId: HouseholdId, filter: CareOccurrenceFilter, page: Int, size: Int) =
        store.values.filter { it.householdId == householdId && !it.dueAt.isBefore(filter.from) && !it.dueAt.isAfter(filter.to) &&
            (filter.petId == null || it.petId == filter.petId) && (filter.type == null || it.type == filter.type) && (filter.status == null || it.status == filter.status) }
            .sortedBy { it.dueAt }.drop(page * size).take(size)
    override fun countByHousehold(householdId: HouseholdId, filter: CareOccurrenceFilter) = searchByHousehold(householdId, filter, 0, Int.MAX_VALUE).size.toLong()
    override fun countByHousehold(householdId: HouseholdId) = store.values.count { it.householdId == householdId }.toLong()
    override fun findUpcomingByHousehold(householdId: HouseholdId, from: Instant, to: Instant, limit: Int) = store.values.filter {
        it.householdId == householdId && it.status == CareOccurrenceStatus.SCHEDULED && !it.dueAt.isBefore(from) && !it.dueAt.isAfter(to)
    }.sortedBy { it.dueAt }.take(limit)
    override fun findCriticalEscalationCandidates(before: Instant, limit: Int) = store.values.filter {
        it.critical && it.status == CareOccurrenceStatus.SCHEDULED && !it.dueAt.isAfter(before)
    }.sortedBy { it.dueAt }.take(limit)

    fun all(): Collection<CareOccurrence> = store.values

    private fun filtered(tutorId: TutorId, filter: CareOccurrenceFilter): List<CareOccurrence> =
        store.values.filter {
            it.tutorId == tutorId && !it.dueAt.isBefore(filter.from) && !it.dueAt.isAfter(filter.to) &&
                (filter.petId == null || it.petId == filter.petId) &&
                (filter.type == null || it.type == filter.type) &&
                (filter.status == null || it.status == filter.status)
        }.sortedBy { it.dueAt }

    private fun cancelMatching(updatedAt: Instant, predicate: (CareOccurrence) -> Boolean): Int {
        val matches = store.values.filter { it.status == CareOccurrenceStatus.SCHEDULED && predicate(it) }
        matches.forEach { store[it.id] = it.cancel(updatedAt) }
        return matches.size
    }
}

internal class InMemoryCarePlanRepo(
    initial: Collection<CarePlan> = emptyList(),
) : CarePlanRepositoryPort {
    private val store = LinkedHashMap<CarePlanId, CarePlan>().apply { initial.forEach { put(it.id, it) } }

    override fun save(plan: CarePlan): CarePlan = plan.also { store[it.id] = it }
    override fun findByIdAndTutor(id: CarePlanId, tutorId: TutorId): CarePlan? =
        store[id]?.takeIf { it.tutorId == tutorId }
    override fun findByIdAndTutorForUpdate(id: CarePlanId, tutorId: TutorId): CarePlan? =
        findByIdAndTutor(id, tutorId)
    override fun listByTutor(tutorId: TutorId, petId: PetId?, active: Boolean?, page: Int, size: Int): List<CarePlan> =
        store.values.filter {
            it.tutorId == tutorId && (petId == null || it.petId == petId) && (active == null || it.active == active)
        }.sortedBy { it.startAt }.drop(page * size).take(size)
    override fun countByTutor(tutorId: TutorId, petId: PetId?, active: Boolean?): Long =
        store.values.count {
            it.tutorId == tutorId && (petId == null || it.petId == petId) && (active == null || it.active == active)
        }.toLong()
    override fun findActive(page: Int, size: Int): List<CarePlan> =
        store.values.filter { it.active }.sortedBy { it.updatedAt }.drop(page * size).take(size)
    override fun findByIdAndHousehold(id: CarePlanId, householdId: HouseholdId) = store[id]?.takeIf { it.householdId == householdId }
    override fun findByIdAndHouseholdForUpdate(id: CarePlanId, householdId: HouseholdId) = findByIdAndHousehold(id, householdId)
    override fun findBySourceDraftId(sourceDraftId: UUID, householdId: HouseholdId) =
        store.values.firstOrNull { it.sourceDraftId == sourceDraftId && it.householdId == householdId }
    override fun listByHousehold(householdId: HouseholdId, petId: PetId?, active: Boolean?, page: Int, size: Int) = store.values.filter {
        it.householdId == householdId && (petId == null || it.petId == petId) && (active == null || it.active == active)
    }.sortedBy { it.startAt }.drop(page * size).take(size)
    override fun countByHousehold(householdId: HouseholdId, petId: PetId?, active: Boolean?) = listByHousehold(householdId, petId, active, 0, Int.MAX_VALUE).size.toLong()
    fun all(): Collection<CarePlan> = store.values
}

internal class InMemoryCareCursorRepo : CarePlanMaterializationCursorRepositoryPort {
    private val store = linkedMapOf<Pair<CarePlanId, Int>, CarePlanMaterializationCursor>()
    override fun save(cursor: CarePlanMaterializationCursor): CarePlanMaterializationCursor = cursor.also {
        store[it.planId to it.scheduleRevision] = it
    }
    override fun find(planId: CarePlanId, scheduleRevision: Int) = store[planId to scheduleRevision]
    override fun findForUpdate(planId: CarePlanId, scheduleRevision: Int) = find(planId, scheduleRevision)
    fun all(): Collection<CarePlanMaterializationCursor> = store.values
}

internal class InMemoryCareOccurrenceActionRepo : CareOccurrenceActionRepositoryPort {
    private val store = LinkedHashMap<UUID, CareOccurrenceAction>()
    override fun findByRequestId(requestId: UUID): CareOccurrenceAction? = store[requestId]
    override fun save(action: CareOccurrenceAction): CareOccurrenceAction = action.also {
        require(!store.containsKey(it.requestId)) { "duplicate_request_id" }
        store[it.requestId] = it
    }
    fun all(): Collection<CareOccurrenceAction> = store.values
}

internal class FakeCareReminderOutbox : CareReminderOutboxPort {
    private val store = LinkedHashMap<CareOccurrenceId, CareReminderOutboxMessage>()
    private val sent = mutableSetOf<Long>()
    private val cancelled = mutableSetOf<Long>()
    private var sequence = 1L
    override fun enqueueIfAbsent(message: CareReminderOutboxMessage) {
        store.putIfAbsent(message.occurrenceId, message.copy(id = sequence++))
    }
    override fun findPendingDelivery(maxAttempts: Int, limit: Int): List<CareReminderOutboxMessage> =
        store.values.filter { it.id !in sent && it.id !in cancelled && it.attempts < maxAttempts }.take(limit)
    override fun markSent(id: Long) { sent += id }
    override fun markCancelled(id: Long, at: Instant) { cancelled += id }
    override fun cancelPendingForPlan(planId: CarePlanId, from: Instant, at: Instant): Int = 0
    override fun incrementAttempts(id: Long) {
        val entry = store.entries.firstOrNull { it.value.id == id } ?: return
        entry.setValue(entry.value.copy(attempts = entry.value.attempts + 1))
    }
    fun all(): Collection<CareReminderOutboxMessage> = store.values
    fun isSent(id: Long?): Boolean = id != null && id in sent
    fun isCancelled(id: Long?): Boolean = id != null && id in cancelled
}

internal class FakeCareEscalationOutbox : CareEscalationOutboxPort {
    private val items = linkedMapOf<CareOccurrenceId, CareEscalationOutboxMessage>()
    override fun enqueueIfAbsent(message: CareEscalationOutboxMessage) { items.putIfAbsent(message.occurrenceId, message.copy(id = items.size + 1L)) }
    override fun findPending(maxAttempts: Int, limit: Int) = items.values.filter { it.attempts < maxAttempts }.take(limit)
    override fun markSent(id: Long) { }
    override fun markCancelled(id: Long, at: Instant) { }
    override fun cancelPendingForPlan(planId: CarePlanId, from: Instant, at: Instant): Int = 0
    override fun incrementAttempts(id: Long) { }
}

internal class FakeHouseholdMemberRepo(
    tutorId: TutorId,
    caregiverIds: List<TutorId> = emptyList(),
) : HouseholdMemberRepositoryPort {
    private val members = (listOf(tutorId) + caregiverIds).mapIndexed { index, memberTutorId ->
        HouseholdMember(
            householdId = TEST_HOUSEHOLD_ID,
            tutorId = memberTutorId,
            role = if (index == 0) HouseholdRole.OWNER else HouseholdRole.CAREGIVER,
            joinedAt = Instant.EPOCH,
        )
    }
    private val member = members.first()
    override fun save(member: HouseholdMember) = member
    override fun findAccess(tutorId: TutorId, householdId: HouseholdId) =
        members.firstOrNull { it.tutorId == tutorId && householdId == TEST_HOUSEHOLD_ID }
    override fun findAccessForUpdate(tutorId: TutorId, householdId: HouseholdId) = findAccess(tutorId, householdId)
    override fun findByIdForUpdate(id: HouseholdMemberId, householdId: HouseholdId) = member.takeIf { id == member.id }
    override fun listDetails(householdId: HouseholdId) = emptyList<HouseholdMemberDetails>()
    override fun count(householdId: HouseholdId) = 1L
    override fun countOwners(householdId: HouseholdId) = 1L
    override fun delete(id: HouseholdMemberId) { }
}

internal class FakeHouseholdActivityRepo : HouseholdActivityRepositoryPort {
    val items = mutableListOf<HouseholdActivity>()
    override fun save(activity: HouseholdActivity) = activity.also(items::add)
    override fun listRecent(householdId: HouseholdId, limit: Int) = emptyList<HouseholdActivityDetails>()
}

/** Sem TTL real: sempre executa o loader — suficiente para testar orquestração. */
internal class FakePlacesCachePort : PlacesCachePort {
    override fun <T> getOrCompute(key: String, ttlSeconds: Long, loader: () -> T): T = loader()
}

internal class FakeGeocodingPort(private val result: GeoLocation?) : GeocodingPort {
    var calls = 0
    override fun geocode(zipCode: String): GeoLocation? {
        calls++
        return result
    }
}

internal class FakePlacesPort(
    private val nearby: List<PlaceSummary> = emptyList(),
    private val detail: PlaceDetails? = null,
    private val reviewList: List<PlaceReview> = emptyList(),
    private val photo: PlacePhoto = PlacePhoto(ByteArray(0), "image/jpeg")
) : PlacesPort {
    var nearbyCalls = 0
    var lastCategory: PlaceCategory? = null

    override fun searchNearby(latitude: Double, longitude: Double, radiusMeters: Int, category: PlaceCategory): List<PlaceSummary> {
        nearbyCalls++
        lastCategory = category
        return nearby
    }
    override fun details(placeId: String): PlaceDetails? = detail
    override fun reviews(placeId: String): List<PlaceReview> = reviewList
    override fun fetchPhoto(photoReference: String, maxWidth: Int): PlacePhoto = photo
}
