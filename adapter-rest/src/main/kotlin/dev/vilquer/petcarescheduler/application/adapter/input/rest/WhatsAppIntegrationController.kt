package dev.vilquer.petcarescheduler.application.adapter.input.rest

import dev.vilquer.petcarescheduler.application.adapter.input.security.CurrentHousehold
import dev.vilquer.petcarescheduler.application.adapter.input.security.CurrentJwt
import dev.vilquer.petcarescheduler.application.service.RateLimitAction
import dev.vilquer.petcarescheduler.application.service.RateLimiterService
import dev.vilquer.petcarescheduler.usecase.contract.drivingports.WhatsAppConnectionUseCase
import dev.vilquer.petcarescheduler.usecase.result.WhatsAppConnectionResult
import dev.vilquer.petcarescheduler.usecase.result.WhatsAppLinkTokenResult
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/integrations/whatsapp")
class WhatsAppIntegrationController(
    private val whatsapp: WhatsAppConnectionUseCase,
    private val household: CurrentHousehold,
    private val rateLimiter: RateLimiterService,
) {
    @GetMapping
    fun status(@AuthenticationPrincipal jwt: CurrentJwt): WhatsAppConnectionResult =
        whatsapp.status(household.resolve(jwt))

    @PostMapping("/link-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    fun createLinkToken(
        @AuthenticationPrincipal jwt: CurrentJwt,
        request: HttpServletRequest,
    ): WhatsAppLinkTokenResult {
        val access = household.resolve(jwt)
        rateLimiter.check(
            RateLimitAction.WHATSAPP_LINK,
            "${request.remoteAddr ?: "unknown"}:${access.actorTutorId.value}:${access.householdId.value}",
        )
        return whatsapp.createLinkToken(access)
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@AuthenticationPrincipal jwt: CurrentJwt) = whatsapp.revoke(household.resolve(jwt))
}
