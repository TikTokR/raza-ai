package com.raza.ai.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

interface HttpTransport {
    @Throws(IOException::class)
    fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse
}

class UrlConnectionTransport : HttpTransport {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val retry = conn.getHeaderField("Retry-After")
            return HttpResponse(status, text, if (retry != null) mapOf("retry-after" to retry) else emptyMap())
        } finally {
            conn.disconnect()
        }
    }
}

data class ApiConfig(val baseUrl: String, val token: String)

sealed class ApiError(val userMessage: String) {
    data object NotConfigured : ApiError("Cloud AI is not set up. Add your RAZA backend address in Settings.")
    data object Offline : ApiError("I can't reach the AI server. Check your internet connection.")
    data object Timeout : ApiError("The AI server took too long to answer.")
    data object Unauthorized : ApiError("The backend rejected the access token. Check it in Settings.")
    data object Forbidden : ApiError("This token is not allowed to do that.")
    data class RateLimited(val retryAfterSeconds: Int?) : ApiError(
        if (retryAfterSeconds != null) "Too many requests. Try again in $retryAfterSeconds seconds." else "Too many requests. Try again shortly."
    )
    data class Validation(val detail: String) : ApiError("The server rejected that request: $detail")
    data object ProviderUnavailable : ApiError("The backend has no AI provider configured yet.")
    data object ProviderTimeout : ApiError("The AI provider took too long to answer.")
    data object ProviderError : ApiError("The AI provider had an error. Please try again.")
    data class Server(val status: Int) : ApiError("The server had a problem (HTTP $status).")
    data object BadResponse : ApiError("The server sent a response I couldn't understand.")
}

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val error: ApiError) : ApiResult<Nothing>()
}

data class HealthInfo(val status: String, val version: String)
data class RemoteAction(val type: String, val params: Map<String, Any?>)
data class RemotePlan(val intent: String, val actions: List<RemoteAction>, val clarification: String?)
data class HistoryItem(val role: String, val content: String)

interface ApiOps {
    fun health(): ApiResult<HealthInfo>
    fun assistant(message: String, language: String, history: List<HistoryItem>): ApiResult<String>
    fun commandPlan(message: String, language: String): ApiResult<RemotePlan>
}

class ApiClient(
    private val config: () -> ApiConfig,
    private val transport: HttpTransport,
    private val timeoutMs: Int = 20_000,
) : ApiOps {

    override fun health(): ApiResult<HealthInfo> =
        when (val r = call("GET", "/api/v1/health", null)) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> {
                val status = r.value.optString("status", "")
                if (status.isEmpty()) ApiResult.Err(ApiError.BadResponse)
                else ApiResult.Ok(HealthInfo(status, r.value.optString("version", "?")))
            }
        }

    override fun assistant(message: String, language: String, history: List<HistoryItem>): ApiResult<String> {
        val hist = JSONArray()
        history.forEach { hist.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("message", message).put("language", language).put("history", hist)
        return when (val r = call("POST", "/api/v1/assistant", body)) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> {
                val reply = r.value.optString("reply", "").trim()
                if (reply.isEmpty()) ApiResult.Err(ApiError.BadResponse) else ApiResult.Ok(reply)
            }
        }
    }

    override fun commandPlan(message: String, language: String): ApiResult<RemotePlan> {
        val body = JSONObject().put("message", message).put("language", language)
        return when (val r = call("POST", "/api/v1/command-plan", body)) {
            is ApiResult.Err -> r
            is ApiResult.Ok -> try {
                val plan = r.value.getJSONObject("plan")
                val actions = plan.optJSONArray("actions") ?: JSONArray()
                val list = (0 until actions.length()).map { i ->
                    val a = actions.getJSONObject(i)
                    RemoteAction(a.getString("type"), a.optJSONObject("params").toKotlinMap())
                }
                ApiResult.Ok(RemotePlan(plan.optString("intent", "NONE"), list, plan.optString("clarification", "").ifBlank { null }))
            } catch (e: JSONException) {
                ApiResult.Err(ApiError.BadResponse)
            }
        }
    }

    private fun call(method: String, path: String, body: JSONObject?): ApiResult<JSONObject> {
        val cfg = config()
        val base = UrlValidator.normalizeBase(cfg.baseUrl) ?: return ApiResult.Err(ApiError.NotConfigured)
        val headers = mutableMapOf("Accept" to "application/json")
        if (body != null) headers["Content-Type"] = "application/json; charset=utf-8"
        if (cfg.token.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.token.trim()}"

        val resp = try {
            transport.request(method, base + path, headers, body?.toString(), timeoutMs)
        } catch (e: SocketTimeoutException) {
            return ApiResult.Err(ApiError.Timeout)
        } catch (e: IOException) {
            return ApiResult.Err(ApiError.Offline)
        }

        val json: JSONObject? = try {
            JSONObject(resp.body)
        } catch (e: JSONException) {
            null
        }

        if (resp.status in 200..299) {
            return if (json != null) ApiResult.Ok(json) else ApiResult.Err(ApiError.BadResponse)
        }

        val code = json?.optJSONObject("error")?.optString("code", "").orEmpty()
        val detail = json?.optJSONObject("error")?.optString("message", "").orEmpty()
        val error: ApiError = when {
            resp.status == 401 -> ApiError.Unauthorized
            resp.status == 403 -> ApiError.Forbidden
            resp.status == 400 || resp.status == 413 || resp.status == 415 -> ApiError.Validation(detail.ifBlank { "invalid request" })
            resp.status == 429 -> ApiError.RateLimited(resp.headers["retry-after"]?.toIntOrNull())
            code == "PROVIDER_UNAVAILABLE" -> ApiError.ProviderUnavailable
            code == "PROVIDER_TIMEOUT" -> ApiError.ProviderTimeout
            code == "PROVIDER_ERROR" -> ApiError.ProviderError
            else -> ApiError.Server(resp.status)
        }
        return ApiResult.Err(error)
    }
}

internal fun JSONObject?.toKotlinMap(): Map<String, Any?> {
    if (this == null) return emptyMap()
    val out = LinkedHashMap<String, Any?>()
    val it = keys()
    while (it.hasNext()) {
        val k = it.next()
        out[k] = if (isNull(k)) null else get(k)
    }
    return out
}
