package dev.vilquer.petcarescheduler.application.exception

/** Falha de um serviço externo (ex.: Google Places retornando erro de quota/permissão). */
class UpstreamServiceException(
    message: String,
    val publicCode: String = "Bad Gateway",
) : RuntimeException(message)
