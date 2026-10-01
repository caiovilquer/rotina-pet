package dev.vilquer.petcarescheduler.application.exception

class RateLimitException(message: String, val publicCode: String = "Too Many Requests") : RuntimeException(message)
