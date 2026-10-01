package dev.vilquer.petcarescheduler.application.adapter.input.rest

import dev.vilquer.petcarescheduler.application.exception.ConflictException
import dev.vilquer.petcarescheduler.application.exception.ForbiddenException
import dev.vilquer.petcarescheduler.application.exception.InvalidCredentialsException
import dev.vilquer.petcarescheduler.application.exception.NotFoundException
import dev.vilquer.petcarescheduler.application.exception.RateLimitException
import dev.vilquer.petcarescheduler.application.exception.UpstreamServiceException
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.converter.HttpMessageNotReadableException

@RestControllerAdvice
class ApiExceptionHandler {

    data class ValidationError(val field: String?, val message: String)
    data class ApiError(
        val status: Int,
        val error: String,
        val message: String?,
        val details: List<ValidationError>? = null
    )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val details = ex.bindingResult.fieldErrors
            .map { ValidationError(it.field, it.defaultMessage ?: "valor inválido") }
        val body = ApiError(400, "Bad Request", "Falha de validação", details)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraint(ex: ConstraintViolationException): ResponseEntity<ApiError> {
        val details = ex.constraintViolations.map { v ->
            ValidationError(v.propertyPath?.toString(), v.message)
        }
        val body = ApiError(400, "Bad Request", "Parâmetros inválidos", details)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<ApiError> {
        val body = ApiError(400, "Bad Request", "JSON mal formado ou tipo inválido")
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ApiError> {
        val body = ApiError(400, "Bad Request", ex.message ?: "Requisicao invalida")
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException): ResponseEntity<ApiError> {
        val body = ApiError(404, "Not Found", ex.message ?: "Recurso nao encontrado")
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ResponseEntity<ApiError> {
        val body = ApiError(401, "Unauthorized", ex.message ?: "Nao autorizado")
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body)
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(ex: DataIntegrityViolationException): ResponseEntity<ApiError> {
        if (isRateLimitIntegrityViolation(ex)) {
            val body = ApiError(503, "Service Unavailable", "Tente novamente em instantes")
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body)
        }
        val message = when (val cause = ex.rootCause) {
            is java.sql.SQLException -> {
                if (cause.sqlState == "23505") "Registro duplicado"
                else "Violação de integridade"
            }
            else -> "Violação de integridade"
        }
        val body = ApiError(409, "Conflict", message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body)
    }

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(ex: OptimisticLockingFailureException): ResponseEntity<ApiError> {
        val body = ApiError(409, "Conflict", "Este registro foi alterado em outro lugar. Atualize a página e tente novamente")
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body)
    }

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ResponseEntity<ApiError> {
        val body = ApiError(409, "Conflict", ex.message ?: "Conflito")
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body)
    }

    @ExceptionHandler(LegacyCareWriteGoneException::class)
    fun handleLegacyCareWriteGone(ex: LegacyCareWriteGoneException): ResponseEntity<ApiError> {
        val body = ApiError(410, "Gone", ex.message)
        return ResponseEntity.status(HttpStatus.GONE).body(body)
    }
    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(ex: ForbiddenException): ResponseEntity<ApiError> {
        val body = ApiError(403, "Forbidden", ex.message)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body)
    }

    @ExceptionHandler(RateLimitException::class)
    fun handleRateLimit(ex: RateLimitException): ResponseEntity<ApiError> {
        val body = ApiError(429, ex.publicCode, ex.message ?: "Limite de tentativas excedido")
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body)
    }

    @ExceptionHandler(UpstreamServiceException::class)
    fun handleUpstream(ex: UpstreamServiceException): ResponseEntity<ApiError> {
        val body = ApiError(502, ex.publicCode, ex.message ?: "Falha ao consultar serviço externo")
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body)
    }

    private fun isRateLimitIntegrityViolation(ex: DataIntegrityViolationException): Boolean {
        val text = buildString {
            var current: Throwable? = ex
            while (current != null) {
                append(current.message.orEmpty())
                append(' ')
                current = current.cause
            }
        }
        return text.contains("rate_limit_attempt", ignoreCase = true)
    }
}
