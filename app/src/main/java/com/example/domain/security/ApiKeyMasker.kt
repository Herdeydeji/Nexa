package com.example.domain.security

import java.net.URI

object ApiKeyMasker {

    fun mask(rawKey: String): String {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= 8) {
            return "••••" + trimmed.takeLast(minOf(2, trimmed.length))
        }
        val prefixLength = when {
            trimmed.startsWith("sk-or-v1-") -> 9
            trimmed.startsWith("sk-proj-") -> 8
            trimmed.startsWith("sk-ant-") -> 7
            trimmed.startsWith("AIza") -> 6
            else -> 4
        }
        val safePrefix = trimmed.take(minOf(prefixLength, trimmed.length - 4))
        val suffix = trimmed.takeLast(4)
        return "$safePrefix••••••••$suffix"
    }

    fun validateFormat(rawKey: String): ValidationResult {
        val trimmed = rawKey.trim()
        return when {
            trimmed.isEmpty() -> ValidationResult.Invalid("API key cannot be empty.")
            trimmed.contains(" ") || trimmed.contains("\n") ->
                ValidationResult.Invalid("API key must not contain whitespace.")
            trimmed.length < 12 ->
                ValidationResult.Invalid("API key looks too short (minimum 12 characters).")
            else -> ValidationResult.Valid
        }
    }

    fun validateEndpointUrl(url: String, required: Boolean): ValidationResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return if (required) {
                ValidationResult.Invalid("Base URL is required for custom compatible providers.")
            } else {
                ValidationResult.Valid
            }
        }
        val parsed = runCatching { URI(trimmed) }.getOrNull()
            ?: return ValidationResult.Invalid("Endpoint URL is malformed.")
        val scheme = parsed.scheme?.lowercase() ?: ""
        val host = parsed.host?.lowercase() ?: ""
        if (host.isBlank()) {
            return ValidationResult.Invalid("Endpoint URL must include a valid host.")
        }
        if (scheme == "https") {
            return ValidationResult.Valid
        }
        if (scheme == "http" && isLocalOrLanHost(host)) {
            return ValidationResult.Valid
        }
        return ValidationResult.Invalid(
            "HTTPS is required for remote endpoints (plain HTTP is allowed only for localhost or local LAN hosts)."
        )
    }

    fun sanitizeErrorText(raw: String, secret: String? = null): String {
        var cleaned = raw
        if (!secret.isNullOrBlank()) {
            cleaned = cleaned.replace(secret.trim(), "[REDACTED_KEY]")
        }
        cleaned = cleaned.replace(Regex("(Bearer\\s+)[A-Za-z0-9._\\-]{8,}"), "$1[REDACTED]")
        cleaned = cleaned.replace(Regex("(sk-[A-Za-z0-9_\\-]{8,})"), "[REDACTED_KEY]")
        cleaned = cleaned.replace(Regex("(AIza[A-Za-z0-9_\\-]{8,})"), "[REDACTED_KEY]")
        return cleaned.trim()
    }

    fun sanitizeTranscriptOrDiagnostics(
        raw: String,
        secretsToMask: List<String> = emptyList()
    ): String {
        var cleaned = sanitizeErrorText(raw)
        secretsToMask.filter { it.isNotBlank() }.forEach { secret ->
            cleaned = cleaned.replace(secret.trim(), "[REDACTED_SECRET]")
        }
        cleaned = cleaned.replace(Regex("\\b\\d{8,12}:[A-Za-z0-9_-]{24,}\\b"), "[REDACTED_BOT_TOKEN]")
        cleaned = cleaned.replace(Regex("([?&](?:key|api_key|token|secret)=)[^&\\s]+", RegexOption.IGNORE_CASE), "$1[REDACTED]")
        cleaned = cleaned.replace(Regex("((?:api[_-]?key|authorization|token|password)\\s*[:=]\\s*)([^\\s,;\"']+)", RegexOption.IGNORE_CASE), "$1[REDACTED]")
        return cleaned
    }

    private fun isLocalOrLanHost(host: String): Boolean {
        return host == "localhost" ||
            host == "127.0.0.1" ||
            host == "10.0.2.2" ||
            host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            host.endsWith(".local")
    }

    sealed interface ValidationResult {
        data object Valid : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }
}
