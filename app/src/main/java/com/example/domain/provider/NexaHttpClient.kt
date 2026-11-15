package com.example.domain.provider

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.domain.security.ApiKeyMasker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

sealed interface ProviderError {
    val userMessage: String

    data class MissingApiKey(override val userMessage: String) : ProviderError
    data class InvalidApiKey(override val userMessage: String) : ProviderError
    data class ProviderUnavailable(
        override val userMessage: String,
        val httpCode: Int? = null
    ) : ProviderError

    data class NetworkUnavailable(override val userMessage: String) : ProviderError
    data class Timeout(override val userMessage: String) : ProviderError
    data class RateLimitExceeded(
        override val userMessage: String,
        val retryAfterSeconds: Int? = null
    ) : ProviderError

    data class UnsupportedModel(
        override val userMessage: String,
        val modelId: String? = null
    ) : ProviderError

    data class InvalidEndpoint(override val userMessage: String) : ProviderError
    data class MalformedResponse(override val userMessage: String) : ProviderError
    data class Cancelled(override val userMessage: String = "Response generation cancelled.") : ProviderError
    data class Unknown(override val userMessage: String) : ProviderError
}

class ProviderException(val error: ProviderError) : Exception(error.userMessage)

interface NetworkAvailabilityChecker {
    fun isNetworkAvailable(): Boolean
}

class AndroidNetworkAvailabilityChecker(
    private val context: Context
) : NetworkAvailabilityChecker {
    override fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

object AlwaysOnlineChecker : NetworkAvailabilityChecker {
    override fun isNetworkAvailable(): Boolean = true
}

data class HttpTextResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String>
)

data class SseFrame(
    val event: String?,
    val data: String
)

class NexaHttpClient(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val networkChecker: NetworkAvailabilityChecker = AlwaysOnlineChecker
) {

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        secretToSanitize: String? = null
    ): Result<HttpTextResponse> = withContext(Dispatchers.IO) {
        if (!networkChecker.isNetworkAvailable()) {
            return@withContext Result.failure(
                ProviderException(
                    ProviderError.NetworkUnavailable(
                        "No active network connection. Check Wi-Fi or mobile data and retry."
                    )
                )
            )
        }
        val request = buildRequest(url, headers).get().build()
        executeTextCall(request, secretToSanitize)
    }

    suspend fun postJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        jsonBody: String,
        secretToSanitize: String? = null
    ): Result<HttpTextResponse> = withContext(Dispatchers.IO) {
        if (!networkChecker.isNetworkAvailable()) {
            return@withContext Result.failure(
                ProviderException(
                    ProviderError.NetworkUnavailable(
                        "No active network connection. Check Wi-Fi or mobile data and retry."
                    )
                )
            )
        }
        val request = buildRequest(url, headers)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeTextCall(request, secretToSanitize)
    }

    suspend fun streamSsePost(
        url: String,
        headers: Map<String, String> = emptyMap(),
        jsonBody: String,
        secretToSanitize: String? = null,
        modelId: String? = null,
        onFrame: suspend (SseFrame) -> Boolean,
        onPlainJsonFallback: suspend (String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!networkChecker.isNetworkAvailable()) {
            return@withContext Result.failure(
                ProviderException(
                    ProviderError.NetworkUnavailable(
                        "No active network connection. Check Wi-Fi or mobile data and retry."
                    )
                )
            )
        }
        val request = buildRequest(url, headers)
            .header("Accept", "text/event-stream, application/json")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = okHttpClient.newCall(request)
        try {
            val response = call.awaitCancellable()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val errorBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    val mapped = mapHttpStatusToError(
                        code = resp.code,
                        body = errorBody,
                        retryAfterHeader = resp.header("Retry-After"),
                        secretToSanitize = secretToSanitize,
                        modelId = modelId
                    )
                    return@withContext Result.failure(ProviderException(mapped))
                }

                val body = resp.body ?: return@withContext Result.failure(
                    ProviderException(
                        ProviderError.MalformedResponse("Provider returned an empty response body.")
                    )
                )

                val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                if (contentType.contains("application/json") && !contentType.contains("event-stream")) {
                    val text = body.string()
                    onPlainJsonFallback(text)
                    return@withContext Result.success(Unit)
                }

                val source = body.source()
                var currentEvent: String? = null
                val dataLines = mutableListOf<String>()
                var sawSseLine = false
                val rawFallbackBuffer = StringBuilder()

                while (true) {
                    if (call.isCanceled()) {
                        return@withContext Result.failure(ProviderException(ProviderError.Cancelled()))
                    }
                    val line = source.readUtf8Line() ?: break
                    if (!sawSseLine && line.trimStart().startsWith("{") && !line.startsWith("data:")) {
                        rawFallbackBuffer.append(line)
                        while (true) {
                            val rest = source.readUtf8Line() ?: break
                            rawFallbackBuffer.append('\n').append(rest)
                        }
                        onPlainJsonFallback(rawFallbackBuffer.toString())
                        return@withContext Result.success(Unit)
                    }

                    if (line.isEmpty()) {
                        if (dataLines.isNotEmpty()) {
                            val payload = dataLines.joinToString("\n")
                            dataLines.clear()
                            val eventName = currentEvent
                            currentEvent = null
                            if (payload == "[DONE]") {
                                return@withContext Result.success(Unit)
                            }
                            val shouldContinue = onFrame(SseFrame(event = eventName, data = payload))
                            if (!shouldContinue) {
                                return@withContext Result.success(Unit)
                            }
                        } else {
                            currentEvent = null
                        }
                    } else if (line.startsWith(":")) {
                        sawSseLine = true
                    } else if (line.startsWith("event:")) {
                        sawSseLine = true
                        currentEvent = line.removePrefix("event:").trim()
                    } else if (line.startsWith("data:")) {
                        sawSseLine = true
                        val value = line.removePrefix("data:").let {
                            if (it.startsWith(" ")) it.substring(1) else it
                        }
                        dataLines.add(value)
                    }
                }

                if (dataLines.isNotEmpty()) {
                    val payload = dataLines.joinToString("\n")
                    if (payload != "[DONE]") {
                        onFrame(SseFrame(event = currentEvent, data = payload))
                    }
                }
                Result.success(Unit)
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (pe: ProviderException) {
            Result.failure(pe)
        } catch (t: Throwable) {
            Result.failure(ProviderException(mapThrowableToError(t, secretToSanitize)))
        }
    }

    private suspend fun executeTextCall(
        request: Request,
        secretToSanitize: String?
    ): Result<HttpTextResponse> {
        val call = okHttpClient.newCall(request)
        return try {
            val response = call.awaitCancellable()
            response.use { resp ->
                val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                if (!resp.isSuccessful) {
                    val mapped = mapHttpStatusToError(
                        code = resp.code,
                        body = body,
                        retryAfterHeader = resp.header("Retry-After"),
                        secretToSanitize = secretToSanitize,
                        modelId = null
                    )
                    Result.failure(ProviderException(mapped))
                } else {
                    val headerMap = resp.headers.names().associateWith { name ->
                        resp.header(name).orEmpty()
                    }
                    Result.success(HttpTextResponse(code = resp.code, body = body, headers = headerMap))
                }
            }
        } catch (ce: CancellationException) {
            call.cancel()
            throw ce
        } catch (t: Throwable) {
            Result.failure(ProviderException(mapThrowableToError(t, secretToSanitize)))
        }
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultOkHttpClient(
            connectTimeoutSeconds: Long = 20,
            readTimeoutSeconds: Long = 90,
            writeTimeoutSeconds: Long = 30,
            callTimeoutSeconds: Long = 180
        ): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .build()

        fun extractErrorMessage(body: String, secretToSanitize: String? = null): String {
            if (body.isBlank()) return ""
            val extracted = runCatching {
                val root = JSONObject(body)
                val errObj = root.optJSONObject("error")
                when {
                    errObj != null -> {
                        errObj.optString("message").takeIf { it.isNotBlank() }
                            ?: errObj.optString("status").takeIf { it.isNotBlank() }
                            ?: errObj.optString("type").takeIf { it.isNotBlank() }
                    }
                    root.has("message") -> root.optString("message").takeIf { it.isNotBlank() }
                    root.has("detail") -> root.optString("detail").takeIf { it.isNotBlank() }
                    else -> null
                }
            }.getOrNull() ?: body.take(240)
            return ApiKeyMasker.sanitizeErrorText(extracted, secretToSanitize)
        }

        fun mapHttpStatusToError(
            code: Int,
            body: String,
            retryAfterHeader: String? = null,
            secretToSanitize: String? = null,
            modelId: String? = null
        ): ProviderError {
            val detail = extractErrorMessage(body, secretToSanitize)
            val lower = detail.lowercase()
            return when (code) {
                401, 403 -> ProviderError.InvalidApiKey(
                    detail.ifBlank { "Authentication failed (HTTP $code). Verify your API key in Connections." }
                )
                404 -> {
                    if (modelId != null || lower.contains("model")) {
                        ProviderError.UnsupportedModel(
                            detail.ifBlank { "Model '${modelId ?: "selected"}' was not found on this provider." },
                            modelId = modelId
                        )
                    } else {
                        ProviderError.InvalidEndpoint(
                            detail.ifBlank { "Endpoint not found (HTTP 404). Verify the provider base URL." }
                        )
                    }
                }
                400, 422 -> {
                    if (lower.contains("model") && (lower.contains("not found") || lower.contains("invalid") || lower.contains("unsupported") || lower.contains("does not exist"))) {
                        ProviderError.UnsupportedModel(
                            detail.ifBlank { "Selected model is unsupported by this provider." },
                            modelId = modelId
                        )
                    } else if (lower.contains("api key") || lower.contains("api_key_invalid")) {
                        ProviderError.InvalidApiKey(detail)
                    } else {
                        ProviderError.ProviderUnavailable(
                            detail.ifBlank { "Provider rejected the request (HTTP $code)." },
                            httpCode = code
                        )
                    }
                }
                429 -> {
                    val retrySec = retryAfterHeader?.toIntOrNull()
                    val suffix = retrySec?.let { " Retry after ${it}s." } ?: ""
                    ProviderError.RateLimitExceeded(
                        (detail.ifBlank { "Rate limit exceeded (HTTP 429)." }) + suffix,
                        retryAfterSeconds = retrySec
                    )
                }
                in 500..599 -> ProviderError.ProviderUnavailable(
                    detail.ifBlank { "Provider server error (HTTP $code). Please retry shortly." },
                    httpCode = code
                )
                else -> ProviderError.Unknown(
                    detail.ifBlank { "Unexpected HTTP error ($code)." }
                )
            }
        }

        fun mapThrowableToError(t: Throwable, secretToSanitize: String? = null): ProviderError {
            val sanitized = ApiKeyMasker.sanitizeErrorText(t.message.orEmpty(), secretToSanitize)
            return when (t) {
                is ProviderException -> t.error
                is SocketTimeoutException -> ProviderError.Timeout(
                    "Request timed out while waiting for the provider. Check connectivity or retry."
                )
                is InterruptedIOException -> {
                    if (sanitized.contains("timeout", ignoreCase = true) ||
                        sanitized.contains("canceled", ignoreCase = true).not()
                    ) {
                        ProviderError.Timeout("Request timed out while communicating with the provider.")
                    } else {
                        ProviderError.Cancelled()
                    }
                }
                is UnknownHostException, is ConnectException -> ProviderError.NetworkUnavailable(
                    "Unable to reach provider host. Verify internet connectivity and endpoint URL."
                )
                is IOException -> {
                    if (sanitized.contains("canceled", ignoreCase = true) ||
                        sanitized.contains("cancelled", ignoreCase = true)
                    ) {
                        ProviderError.Cancelled()
                    } else {
                        ProviderError.NetworkUnavailable(
                            sanitized.ifBlank { "Network error while contacting provider." }
                        )
                    }
                }
                is IllegalArgumentException -> ProviderError.InvalidEndpoint(
                    sanitized.ifBlank { "Invalid endpoint URL." }
                )
                else -> ProviderError.Unknown(
                    sanitized.ifBlank { "Unexpected error while communicating with provider." }
                )
            }
        }
    }
}

private suspend fun Call.awaitCancellable(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCompleted) {
                cont.resumeWith(Result.failure(e))
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (!cont.isCompleted) {
                cont.resume(response)
            } else {
                response.close()
            }
        }
    })
}
