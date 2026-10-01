package dev.vilquer.petcarescheduler.application.config

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.JwtParser
import dev.vilquer.petcarescheduler.application.security.JwtCacheProperties
import dev.vilquer.petcarescheduler.application.security.PasswordChangedAtCache
import dev.vilquer.petcarescheduler.core.domain.entity.TutorId
import dev.vilquer.petcarescheduler.usecase.contract.drivenports.TutorRepositoryPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import java.util.regex.Pattern

@Configuration
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
class SecurityConfig {

    @Bean
    fun filterChain(
        http: HttpSecurity,
        jwtDecoder: JwtDecoder,
        bearerTokenResolver: BearerTokenResolver
    ): SecurityFilterChain {
        http
            .cors { }
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout-all").authenticated()
                    .requestMatchers(
                        "/api/v1/public/**",
                        "/api/v1/auth/**",
                        "/api/v1/auth/password/forgot",
                        "/api/v1/auth/password/reset",
                        "/api/v1/auth/password/reset/validate",
                        "/actuator/health",
                        // Exceção deliberada dentro de /api/v1/locations/**: uma tag <img>
                        // não anexa o header Authorization do interceptor Angular, então
                        // esse endpoint não pode exigir JWT. O custo real (busca/geocoding/
                        // details) continua atrás de autenticação — aqui só trafegam bytes
                        // de foto, e o photo_reference só é obtido chamando os endpoints
                        // autenticados primeiro.
                        "/api/v1/locations/photo",
                        "/api/v1/media/*/content",
                        "/api/v1/webhooks/whatsapp"
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .headers { headers ->
                headers.frameOptions { it.sameOrigin() }
            }
            .oauth2ResourceServer { rs ->
                rs.bearerTokenResolver(bearerTokenResolver)
                rs.jwt { jwt ->
                    jwt.decoder(jwtDecoder)
                }
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }

        return http.build()
    }

    @Bean
    fun bearerTokenResolver(): BearerTokenResolver {
        val delegate = DefaultBearerTokenResolver()
        return BearerTokenResolver { request ->
            resolveFromHeader(request.getHeader(HttpHeaders.AUTHORIZATION))
                ?: resolveFromHeader(request.getHeader("X-Authorization"))
                ?: resolveFromHeader(request.getHeader("X-Auth-Token"))
                ?: resolveFromHeader(request.getHeader("X-Access-Token"))
                ?: delegate.resolve(request)
        }
    }

    @Bean
    fun jwtDecoder(
        jwtParser: JwtParser,
        tutorRepo: TutorRepositoryPort,
        passwordChangedAtCache: PasswordChangedAtCache,
        jwtCacheProperties: JwtCacheProperties
    ): JwtDecoder {
        return JwtDecoder { token ->
            try {
                val jws = jwtParser.parseSignedClaims(token)
                val headers = jws.header
                val claims = jws.payload
                val subject = claims.subject?.toLongOrNull()
                    ?: throw BadJwtException("JWT sem subject valido")

                val issuedAt = claims.issuedAt?.toInstant()
                    ?: throw BadJwtException("JWT sem iat")
                if (jwtCacheProperties.passwordChangedAtCheckEnabled) {
                    // Consulta escalar: no filtro não existe sessão Hibernate
                    // (open-in-view só cobre o MVC), então carregar o agregado
                    // Tutor aqui estoura LazyInitializationException
                    val passwordChangedAt = passwordChangedAtCache.getOrLoad(subject) {
                        tutorRepo.findPasswordChangedAt(TutorId(subject))
                            ?: throw BadJwtException("Tutor nao encontrado")
                    }
                    val skew = jwtCacheProperties.invalidationSkew
                    if (passwordChangedAt != null && issuedAt.isBefore(passwordChangedAt.minus(skew))) {
                        throw BadJwtException("JWT expirado por troca de senha")
                    }
                }

                Jwt(
                    token,
                    issuedAt,
                    claims.expiration?.toInstant(),
                    headers,
                    claims
                )
            } catch (e: JwtException) {
                throw BadJwtException("JWT inválido: ${e.message}", e)
            }
        }
    }

    companion object {
        private val JWT_PATTERN = Pattern.compile("^[A-Za-z0-9-_]+=*\\.[A-Za-z0-9-_]+=*\\.[A-Za-z0-9-_]+=*$")
        private val SCHEMES = setOf("bearer", "token", "jwt")

        private fun resolveFromHeader(header: String?): String? {
            if (header.isNullOrBlank()) return null
            var candidate = header.trim()

            val parts = candidate.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && SCHEMES.contains(parts[0].lowercase())) {
                candidate = parts[1]
            }

            candidate = stripQuotes(candidate)
            while (true) {
                val normalized = candidate.lowercase()
                SCHEMES.firstOrNull { normalized.startsWith("$it ") } ?: break
                candidate = stripQuotes(candidate.substringAfter(' ').trim())
                if (candidate.isEmpty()) return null
            }

            return candidate.takeIf { JWT_PATTERN.matcher(it).matches() }
        }

        private fun stripQuotes(value: String): String =
            value.trim().trim('"', '\'')
    }
}
