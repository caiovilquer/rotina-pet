package dev.vilquer.petcarescheduler.application.config

import dev.vilquer.petcarescheduler.application.service.AuthAppService
import dev.vilquer.petcarescheduler.application.service.CareAppService
import dev.vilquer.petcarescheduler.application.service.CareDraftAppService
import dev.vilquer.petcarescheduler.application.service.CareDraftSettings
import dev.vilquer.petcarescheduler.application.service.CareReminderRelayService
import dev.vilquer.petcarescheduler.application.service.CareEscalationRelayService
import dev.vilquer.petcarescheduler.application.service.EventAppService
import dev.vilquer.petcarescheduler.application.service.DashboardAppService
import dev.vilquer.petcarescheduler.application.service.LocationAppService
import dev.vilquer.petcarescheduler.application.service.HealthAppService
import dev.vilquer.petcarescheduler.application.service.HouseholdAppService
import dev.vilquer.petcarescheduler.application.service.MediaAppService
import dev.vilquer.petcarescheduler.application.service.PasswordResetService
import dev.vilquer.petcarescheduler.application.service.PetAppService
import dev.vilquer.petcarescheduler.application.service.RateLimitProperties
import dev.vilquer.petcarescheduler.application.service.RateLimiterService
import dev.vilquer.petcarescheduler.application.service.ReminderRelayService
import dev.vilquer.petcarescheduler.application.service.SecurityMaintenanceService
import dev.vilquer.petcarescheduler.application.service.TutorAppService
import dev.vilquer.petcarescheduler.application.service.FinanceAppService
import dev.vilquer.petcarescheduler.application.service.VeterinaryReportAppService
import dev.vilquer.petcarescheduler.application.service.KnowledgeIndexSettings
import dev.vilquer.petcarescheduler.application.service.KnowledgeIndexingService
import dev.vilquer.petcarescheduler.application.service.PetHistoryAssistantService
import dev.vilquer.petcarescheduler.application.service.PetHistoryAssistantSettings
import dev.vilquer.petcarescheduler.application.service.StructuredPetHistoryCatalog
import dev.vilquer.petcarescheduler.application.service.WhatsAppAppService
import dev.vilquer.petcarescheduler.application.service.WhatsAppSettings
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ClockPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareOccurrenceActionRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareOccurrenceRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CarePlanRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CarePlanMaterializationCursorRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareDraftRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AiInteractionRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareInstructionExtractorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareReminderOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.CareEscalationOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EventRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GeocodingPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthMeasurementRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordAttachmentRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HealthRecordRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdMemberRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdInvitationRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdActivityRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdHandoffRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.HouseholdInvitationNotifierPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.HouseholdManagementUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.NotificationPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.MediaAssetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ObjectStoragePort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PasswordHashPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PasswordResetNotifierPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PasswordResetTokenPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PetRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PlacesCachePort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.PlacesPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.RateLimitStorePort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.RefreshTokenPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ReminderOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TokenIssuerPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TransactionPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TutorRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.ExpenseRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.VeterinaryShareRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.AssistantAnswerRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.DocumentTextExtractorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.EmbeddingPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.GroundedAnswerGeneratorPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeIndexOutboxPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.KnowledgeSourceRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.SemanticSearchPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppConversationRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppCryptoPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppGatewayPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppIdentityRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppInboxRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppLinkTokenRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.WhatsAppOutboxRepositoryPort
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.CareDraftUseCase
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.FinanceOverviewUseCase
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration

/**
 * O módulo `application` é Kotlin puro (sem Spring): os services não carregam
 * `@Service`/`@Value`/`@ConfigurationProperties`. Este é o único lugar onde o
 * grafo de use cases é montado — a fronteira hexagonal fica no wiring, não em
 * anotações espalhadas pelos services.
 */
@Configuration
class UseCaseWiring {

    @Bean
    fun whatsappSettings(environment: Environment) = WhatsAppSettings(
        enabled = environment.getProperty("app.whatsapp.enabled", Boolean::class.java, false),
        businessPhoneNumberId = environment.getProperty("app.whatsapp.business-phone-number-id", ""),
        businessPhoneNumber = environment.getProperty("app.whatsapp.business-phone-number", ""),
        linkTtl = environment.getProperty("app.whatsapp.link-ttl", Duration::class.java, Duration.ofMinutes(10)),
        conversationTtl = environment.getProperty("app.whatsapp.conversation-ttl", Duration::class.java, Duration.ofHours(24)),
        inboxBatchSize = environment.getProperty("app.whatsapp.inbox-batch-size", Int::class.java, 20),
        outboxBatchSize = environment.getProperty("app.whatsapp.outbox-batch-size", Int::class.java, 20),
        maxAttempts = environment.getProperty("app.whatsapp.max-attempts", Int::class.java, 5),
    )

    @Bean
    fun whatsappAppService(
        linkTokens: WhatsAppLinkTokenRepositoryPort,
        identities: WhatsAppIdentityRepositoryPort,
        conversations: WhatsAppConversationRepositoryPort,
        inbox: WhatsAppInboxRepositoryPort,
        outbox: WhatsAppOutboxRepositoryPort,
        crypto: WhatsAppCryptoPort,
        gateway: WhatsAppGatewayPort,
        careDrafts: CareDraftUseCase,
        households: HouseholdRepositoryPort,
        members: HouseholdMemberRepositoryPort,
        transaction: TransactionPort,
        clock: ClockPort,
        settings: WhatsAppSettings,
    ) = WhatsAppAppService(
        linkTokens, identities, conversations, inbox, outbox, crypto, gateway, careDrafts,
        households, members, transaction, clock, settings,
    )

    @Bean
    fun petHistoryAssistantSettings(environment: Environment) = PetHistoryAssistantSettings(
        ragEnabled = environment.getProperty("app.ai.rag-enabled", Boolean::class.java, false),
        maxQuestionCharacters = environment.getProperty("app.ai.max-question-characters", Int::class.java, 1_000),
        retrievalLimit = environment.getProperty("app.ai.retrieval-limit", Int::class.java, 5),
        minimumSemanticScore = environment.getProperty("app.ai.minimum-semantic-score", Double::class.java, 0.08),
    )

    @Bean
    fun knowledgeIndexSettings(environment: Environment) = KnowledgeIndexSettings(
        enabled = environment.getProperty("app.ai.rag-enabled", Boolean::class.java, false),
        batchSize = environment.getProperty("app.ai.index-batch-size", Int::class.java, 10),
        maxAttempts = environment.getProperty("app.ai.index-max-attempts", Int::class.java, 5),
    )

    @Bean
    fun structuredPetHistoryCatalog(
        records: HealthRecordRepositoryPort,
        measurements: HealthMeasurementRepositoryPort,
        occurrences: CareOccurrenceRepositoryPort,
        finances: FinanceOverviewUseCase,
        plans: CarePlanRepositoryPort,
        members: HouseholdMemberRepositoryPort,
    ) = StructuredPetHistoryCatalog(records, measurements, occurrences, finances, plans, members)

    @Bean
    fun petHistoryAssistantService(
        catalog: StructuredPetHistoryCatalog,
        pets: PetRepositoryPort,
        embeddings: EmbeddingPort,
        search: SemanticSearchPort,
        generator: GroundedAnswerGeneratorPort,
        answers: AssistantAnswerRepositoryPort,
        interactions: AiInteractionRepositoryPort,
        transaction: TransactionPort,
        clock: ClockPort,
        settings: PetHistoryAssistantSettings,
        rateLimiter: RateLimiterService,
    ) = PetHistoryAssistantService(
        catalog, pets, embeddings, search, generator, answers, interactions, transaction, clock, settings, rateLimiter,
    )

    @Bean
    fun knowledgeIndexingService(
        sources: KnowledgeSourceRepositoryPort,
        outbox: KnowledgeIndexOutboxPort,
        records: HealthRecordRepositoryPort,
        media: MediaAssetRepositoryPort,
        storage: ObjectStoragePort,
        extractor: DocumentTextExtractorPort,
        embeddings: EmbeddingPort,
        transaction: TransactionPort,
        clock: ClockPort,
        settings: KnowledgeIndexSettings,
    ) = KnowledgeIndexingService(sources, outbox, records, media, storage, extractor, embeddings, transaction, clock, settings)

    @Bean
    fun careDraftSettings(environment: Environment) = CareDraftSettings(
        enabled = environment.getProperty("app.ai.enabled", Boolean::class.java, false),
        ttl = environment.getProperty("app.ai.draft-ttl", Duration::class.java, Duration.ofHours(24)),
        maxInputCharacters = environment.getProperty("app.ai.max-input-characters", Int::class.java, 4_000),
    )

    @Bean
    fun careDraftAppService(
        drafts: CareDraftRepositoryPort,
        interactions: AiInteractionRepositoryPort,
        extractor: CareInstructionExtractorPort,
        carePlans: dev.vilquer.petcarescheduler.usecase.contract.drivingports.CarePlanUseCase,
        pets: PetRepositoryPort,
        members: HouseholdMemberRepositoryPort,
        transaction: TransactionPort,
        clock: ClockPort,
        settings: CareDraftSettings,
        rateLimiter: RateLimiterService,
    ) = CareDraftAppService(drafts, interactions, extractor, carePlans, pets, members, transaction, clock, settings, rateLimiter)

    @Bean
    fun financeAppService(
        expenses: ExpenseRepositoryPort,
        healthRecords: HealthRecordRepositoryPort,
        occurrences: CareOccurrenceRepositoryPort,
        pets: PetRepositoryPort,
        transaction: TransactionPort,
        clock: ClockPort,
    ) = FinanceAppService(expenses, healthRecords, occurrences, pets, transaction, clock)

    @Bean
    fun veterinaryReportAppService(
        records: HealthRecordRepositoryPort,
        measurements: HealthMeasurementRepositoryPort,
        attachments: HealthRecordAttachmentRepositoryPort,
        occurrences: CareOccurrenceRepositoryPort,
        pets: PetRepositoryPort,
        shares: VeterinaryShareRepositoryPort,
        media: MediaAssetRepositoryPort,
        storage: ObjectStoragePort,
        transaction: TransactionPort,
        clock: ClockPort,
        households: HouseholdRepositoryPort,
    ) = VeterinaryReportAppService(records, measurements, attachments, occurrences, pets, shares, media, storage, transaction, clock, households)

    @Bean
    fun householdAppService(
        households: HouseholdRepositoryPort,
        members: HouseholdMemberRepositoryPort,
        invitations: HouseholdInvitationRepositoryPort,
        activities: HouseholdActivityRepositoryPort,
        handoffs: HouseholdHandoffRepositoryPort,
        tutors: TutorRepositoryPort,
        notifier: HouseholdInvitationNotifierPort,
        transaction: TransactionPort,
        clock: ClockPort,
    ) = HouseholdAppService(households, members, invitations, activities, handoffs, tutors, notifier, transaction, clock)

    @Bean
    fun careAppService(
        plans: CarePlanRepositoryPort,
        cursors: CarePlanMaterializationCursorRepositoryPort,
        occurrences: CareOccurrenceRepositoryPort,
        actions: CareOccurrenceActionRepositoryPort,
        pets: PetRepositoryPort,
        tutors: TutorRepositoryPort,
        reminderOutbox: CareReminderOutboxPort,
        escalationOutbox: CareEscalationOutboxPort,
        householdMembers: HouseholdMemberRepositoryPort,
        householdActivities: HouseholdActivityRepositoryPort,
        transaction: TransactionPort,
        clock: ClockPort,
    ) = CareAppService(plans, cursors, occurrences, actions, pets, tutors, reminderOutbox, escalationOutbox, householdMembers, householdActivities, transaction, clock)

    @Bean
    fun careEscalationRelayService(
        outbox: CareEscalationOutboxPort,
        occurrences: CareOccurrenceRepositoryPort,
        notifier: NotificationPort,
        activities: HouseholdActivityRepositoryPort,
        clock: ClockPort,
        members: HouseholdMemberRepositoryPort,
    ) = CareEscalationRelayService(outbox, occurrences, notifier, activities, clock, members)

    @Bean
    fun careReminderRelayService(
        outbox: CareReminderOutboxPort,
        occurrences: CareOccurrenceRepositoryPort,
        notifier: NotificationPort,
        members: HouseholdMemberRepositoryPort,
    ) = CareReminderRelayService(outbox, occurrences, notifier, members)

    @Bean
    fun authAppService(
        tutorRepo: TutorRepositoryPort,
        passwordHash: PasswordHashPort,
        tokenIssuer: TokenIssuerPort,
        refreshTokens: RefreshTokenPort,
        transactionPort: TransactionPort,
        environment: Environment,
    ) = AuthAppService(
        tutorRepo, passwordHash, tokenIssuer, refreshTokens, transactionPort,
        refreshTtl = Binder.get(environment)
            .bind("app.security.refresh.ttl", Duration::class.java)
            .orElse(Duration.ofDays(30)),
    )

    @Bean
    fun eventAppService(
        eventRepo: EventRepositoryPort,
        petRepo: PetRepositoryPort,
        clock: ClockPort,
        outbox: ReminderOutboxPort,
        transactionPort: TransactionPort,
    ) = EventAppService(eventRepo, petRepo, clock, outbox, transactionPort)

    @Bean
    fun dashboardAppService(
        tutorRepo: TutorRepositoryPort,
        petRepo: PetRepositoryPort,
        occurrences: CareOccurrenceRepositoryPort,
        clock: ClockPort,
    ) = DashboardAppService(tutorRepo, petRepo, occurrences, clock)

    @Bean
    fun mediaAppService(
        media: MediaAssetRepositoryPort,
        storage: ObjectStoragePort,
        pets: PetRepositoryPort,
        tutors: TutorRepositoryPort,
        healthRecords: HealthRecordRepositoryPort,
        healthAttachments: HealthRecordAttachmentRepositoryPort,
        transaction: TransactionPort,
        knowledgeSources: KnowledgeSourceRepositoryPort,
        knowledgeOutbox: KnowledgeIndexOutboxPort,
        embeddings: EmbeddingPort,
    ) = MediaAppService(
        media, storage, pets, tutors, healthRecords, healthAttachments, transaction,
        knowledgeSources = knowledgeSources,
        knowledgeOutbox = knowledgeOutbox,
        embeddingSignature = embeddings.signature,
    )

    @Bean
    fun healthAppService(
        records: HealthRecordRepositoryPort,
        measurements: HealthMeasurementRepositoryPort,
        attachments: HealthRecordAttachmentRepositoryPort,
        media: MediaAssetRepositoryPort,
        pets: PetRepositoryPort,
        transaction: TransactionPort,
        clock: ClockPort,
        activities: HouseholdActivityRepositoryPort,
        knowledgeSources: KnowledgeSourceRepositoryPort,
        knowledgeOutbox: KnowledgeIndexOutboxPort,
        embeddings: EmbeddingPort,
    ) = HealthAppService(
        records, measurements, attachments, media, pets, transaction, clock, activities,
        knowledgeSources, knowledgeOutbox, embeddings.signature,
    )

    @Bean
    fun reminderRelayService(
        outbox: ReminderOutboxPort,
        eventRepo: EventRepositoryPort,
        notifier: NotificationPort,
    ) = ReminderRelayService(outbox, eventRepo, notifier)

    @Bean
    fun petAppService(
        petRepo: PetRepositoryPort,
        tutorRepo: TutorRepositoryPort,
        eventRepo: EventRepositoryPort,
    ) = PetAppService(petRepo, tutorRepo, eventRepo)

    @Bean
    fun tutorAppService(
        tutorRepo: TutorRepositoryPort,
        passwordHash: PasswordHashPort,
        petRepo: PetRepositoryPort,
        householdManagement: HouseholdManagementUseCase,
        transaction: TransactionPort,
    ) = TutorAppService(tutorRepo, passwordHash, petRepo, householdManagement, transaction)

    @Bean
    fun rateLimitProperties(environment: Environment): RateLimitProperties =
        Binder.get(environment)
            .bind("app.security.rate-limit", RateLimitProperties::class.java)
            .orElseGet { RateLimitProperties() }

    @Bean
    fun rateLimiterService(
        props: RateLimitProperties,
        store: RateLimitStorePort,
    ) = RateLimiterService(props, store)

    @Bean
    fun passwordResetService(
        tutors: TutorRepositoryPort,
        tokens: PasswordResetTokenPort,
        notifier: PasswordResetNotifierPort,
        passwordHash: PasswordHashPort,
        transactionPort: TransactionPort,
        environment: Environment,
    ) = PasswordResetService(
        tutors, tokens, notifier, passwordHash, transactionPort,
        ttlMinutes = environment.getProperty("app.reset.ttl-minutes", Long::class.java, 30L),
    )

    @Bean
    fun securityMaintenanceService(
        rateLimiter: RateLimiterService,
        resetTokens: PasswordResetTokenPort,
        refreshTokens: RefreshTokenPort,
    ) = SecurityMaintenanceService(rateLimiter, resetTokens, refreshTokens)

    @Bean
    fun locationAppService(
        geocoding: GeocodingPort,
        places: PlacesPort,
        cache: PlacesCachePort,
    ) = LocationAppService(geocoding, places, cache)
}
