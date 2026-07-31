package dev.vilquer.petcarescheduler.application.service

import dev.vilquer.petcarescheduler.application.exception.RateLimitException
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.RateLimitStorePort
import java.time.Clock
import java.time.Duration
import java.time.Instant

// Binding a partir de application.yml (app.security.rate-limit) é feito no bootstrap.
data class RateLimitProperties(
    val login: RateLimitConfig = RateLimitConfig(),
    val passwordReset: RateLimitConfig = RateLimitConfig(),
    val tokenRefresh: RateLimitConfig = RateLimitConfig(),
    val mediaUpload: RateLimitConfig = RateLimitConfig(maxAttempts = 20, window = Duration.ofHours(1)),
    val householdInvite: RateLimitConfig = RateLimitConfig(maxAttempts = 10, window = Duration.ofHours(1)),
    val veterinaryShareCreate: RateLimitConfig = RateLimitConfig(maxAttempts = 10, window = Duration.ofHours(1)),
    val veterinaryShareAccess: RateLimitConfig = RateLimitConfig(maxAttempts = 60, window = Duration.ofMinutes(15)),
    val assistantQuestion: RateLimitConfig = RateLimitConfig(maxAttempts = 20, window = Duration.ofHours(1)),
    val assistantDraft: RateLimitConfig = RateLimitConfig(maxAttempts = 10, window = Duration.ofHours(1)),
    val whatsappLink: RateLimitConfig = RateLimitConfig(maxAttempts = 10, window = Duration.ofHours(1)),
    val whatsappWebhook: RateLimitConfig = RateLimitConfig(maxAttempts = 600, window = Duration.ofMinutes(1)),
)

data class RateLimitConfig(
    val maxAttempts: Int = 5,
    val window: Duration = Duration.ofMinutes(15)
)

enum class RateLimitAction {
    LOGIN, PASSWORD_RESET, TOKEN_REFRESH, MEDIA_UPLOAD, HOUSEHOLD_INVITE,
    VETERINARY_SHARE_CREATE, VETERINARY_SHARE_ACCESS, ASSISTANT_QUESTION, ASSISTANT_DRAFT,
    WHATSAPP_LINK, WHATSAPP_WEBHOOK,
}

class RateLimiterService(
    private val props: RateLimitProperties,
    private val store: RateLimitStorePort,
    private val clock: Clock = Clock.systemUTC()
) {
    fun check(action: RateLimitAction, key: String) {
        val limit = when (action) {
            RateLimitAction.LOGIN -> props.login
            RateLimitAction.PASSWORD_RESET -> props.passwordReset
            RateLimitAction.TOKEN_REFRESH -> props.tokenRefresh
            RateLimitAction.MEDIA_UPLOAD -> props.mediaUpload
            RateLimitAction.HOUSEHOLD_INVITE -> props.householdInvite
            RateLimitAction.VETERINARY_SHARE_CREATE -> props.veterinaryShareCreate
            RateLimitAction.VETERINARY_SHARE_ACCESS -> props.veterinaryShareAccess
            RateLimitAction.ASSISTANT_QUESTION -> props.assistantQuestion
            RateLimitAction.ASSISTANT_DRAFT -> props.assistantDraft
            RateLimitAction.WHATSAPP_LINK -> props.whatsappLink
            RateLimitAction.WHATSAPP_WEBHOOK -> props.whatsappWebhook
        }
        val id = "${action.name}:${key}"
        val count = store.registerAttempt(id, clock.instant(), limit.window)
        if (count > limit.maxAttempts) {
            throw RateLimitException(
                "Limite de tentativas excedido, tente novamente mais tarde",
                if (action == RateLimitAction.ASSISTANT_QUESTION || action == RateLimitAction.ASSISTANT_DRAFT) "AI_RATE_LIMITED" else "Too Many Requests",
            )
        }
    }

    fun reset(action: RateLimitAction, key: String) {
        store.delete("${action.name}:$key")
    }

    fun cleanup() {
        val maxWindow = maxOf(
            props.login.window, props.passwordReset.window, props.tokenRefresh.window, props.mediaUpload.window,
            props.householdInvite.window, props.veterinaryShareCreate.window, props.veterinaryShareAccess.window,
            props.assistantQuestion.window, props.assistantDraft.window,
            props.whatsappLink.window, props.whatsappWebhook.window,
        )
        store.deleteOlderThan(clock.instant().minus(maxWindow))
    }
}
