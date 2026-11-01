package com.raza.ai.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FakeTransport(private val handler: (method: String, url: String, headers: Map<String, String>, body: String?) -> HttpResponse) : HttpTransport {
    var lastMethod: String? = null
    var lastUrl: String? = null
    var lastHeaders: Map<String, String> = emptyMap()
    var lastBody: String? = null
    var calls = 0
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse {
        calls++
        lastMethod = method; lastUrl = url; lastHeaders = headers; lastBody = body
        return handler(method, url, headers, body)
    }
}

class ApiClientTest {
    private val token = "secret-token-0123456789"
    private fun client(t: HttpTransport, base: String = "https://raza.example.com", tok: String = token) =
        ApiClient({ ApiConfig(base, tok) }, t)

    private fun ok(json: String) = HttpResponse(200, json)
    private fun err(status: Int, code: String, msg: String = "m", headers: Map<String, String> = emptyMap()) =
        HttpResponse(status, """{"error":{"code":"$code","message":"$msg"},"requestId":"r"}""", headers)

    private fun <T> ApiResult<T>.errOf(): ApiError = (this as ApiResult.Err).error
    private fun <T> ApiResult<T>.value(): T = (this as ApiResult.Ok).value

    // ----- health -----
    @Test fun healthHitsCorrectUrlWithoutBodyAndParses() {
        val t = FakeTransport { _, _, _, _ -> ok("""{"status":"ok","version":"1.0.0"}""") }
        val r = client(t).health()
        assertEquals(HealthInfo("ok", "1.0.0"), r.value())
        assertEquals("GET", t.lastMethod)
        assertEquals("https://raza.example.com/api/v1/health", t.lastUrl)
        assertNull(t.lastBody)
    }

    @Test fun baseUrlWithApiV1SuffixAndSlashIsNormalised() {
        val t = FakeTransport { _, _, _, _ -> ok("""{"status":"ok"}""") }
        client(t, "https://raza.example.com/api/v1/").health()
        assertEquals("https://raza.example.com/api/v1/health", t.lastUrl)
    }

    // ----- assistant -----
    @Test fun assistantSendsBodyHeadersAndParsesReply() {
        val t = FakeTransport { _, _, _, _ -> ok("""{"reply":"Salam!","mode":"cloud"}""") }
        val r = client(t).assistant("hello", "Urdu", listOf(HistoryItem("user", "a"), HistoryItem("assistant", "b")))
        assertEquals("Salam!", r.value())
        assertEquals("POST", t.lastMethod)
        assertEquals("https://raza.example.com/api/v1/assistant", t.lastUrl)
        assertEquals("Bearer $token", t.lastHeaders["Authorization"])
        assertTrue(t.lastHeaders["Content-Type"]!!.startsWith("application/json"))
        val sent = JSONObject(t.lastBody!!)
        assertEquals("hello", sent.getString("message"))
        assertEquals("Urdu", sent.getString("language"))
        assertEquals(2, sent.getJSONArray("history").length())
    }

    @Test fun noAuthHeaderWhenTokenBlank() {
        val t = FakeTransport { _, _, _, _ -> ok("""{"reply":"x"}""") }
        client(t, tok = "  ").assistant("hi", "English", emptyList())
        assertFalse(t.lastHeaders.containsKey("Authorization"))
    }

    // ----- configuration -----
    @Test fun notConfiguredDoesNoNetworkCall() {
        val t = FakeTransport { _, _, _, _ -> error("must not be called") }
        for (bad in listOf("", "   ", "http://insecure.example.com", "not a url", "https://user:pw@host.example.com", "ftp://x.example.com")) {
            assertEquals("'$bad'", ApiError.NotConfigured, client(t, bad).assistant("hi", "English", emptyList()).errOf())
        }
        assertEquals(0, t.calls)
    }

    // ----- error mapping -----
    @Test fun httpStatusMapping() {
        fun run(resp: HttpResponse) = client(FakeTransport { _, _, _, _ -> resp }).assistant("hi", "English", emptyList()).errOf()
        assertEquals(ApiError.Unauthorized, run(err(401, "UNAUTHORIZED")))
        assertEquals(ApiError.Forbidden, run(err(403, "FORBIDDEN")))
        assertEquals(ApiError.Validation("bad field"), run(err(400, "VALIDATION_ERROR", "bad field")))
        assertEquals(ApiError.RateLimited(12), run(err(429, "RATE_LIMITED", headers = mapOf("retry-after" to "12"))))
        assertEquals(ApiError.RateLimited(null), run(err(429, "RATE_LIMITED")))
        assertEquals(ApiError.ProviderUnavailable, run(err(503, "PROVIDER_UNAVAILABLE")))
        assertEquals(ApiError.ProviderTimeout, run(err(504, "PROVIDER_TIMEOUT")))
        assertEquals(ApiError.ProviderError, run(err(502, "PROVIDER_ERROR")))
        assertEquals(ApiError.Server(500), run(err(500, "INTERNAL_ERROR")))
        assertEquals(ApiError.Server(503), run(err(503, "AUTH_NOT_CONFIGURED")))
    }

    @Test fun rateLimitMessageMentionsDelay() =
        assertTrue(ApiError.RateLimited(12).userMessage.contains("12"))

    @Test fun nonJsonErrorBodyStillMapsByStatus() {
        val t = FakeTransport { _, _, _, _ -> HttpResponse(502, "<html>Bad gateway</html>") }
        assertEquals(ApiError.Server(502), client(t).assistant("hi", "English", emptyList()).errOf())
    }

    // ----- network failures -----
    @Test fun timeoutIsReportedAsTimeout() {
        val t = FakeTransport { _, _, _, _ -> throw SocketTimeoutException("slow") }
        assertEquals(ApiError.Timeout, client(t).assistant("hi", "English", emptyList()).errOf())
    }

    @Test fun connectionFailureIsReportedAsOffline() {
        for (e in listOf<IOException>(UnknownHostException("x"), IOException("reset"))) {
            val t = FakeTransport { _, _, _, _ -> throw e }
            assertEquals(ApiError.Offline, client(t).health().errOf())
        }
    }

    // ----- malformed responses -----
    @Test fun malformedSuccessBodiesAreBadResponse() {
        fun a(body: String) = client(FakeTransport { _, _, _, _ -> ok(body) }).assistant("hi", "English", emptyList()).errOf()
        assertEquals(ApiError.BadResponse, a("not json"))
        assertEquals(ApiError.BadResponse, a("""{"mode":"cloud"}"""))
        assertEquals(ApiError.BadResponse, a("""{"reply":"   "}"""))
        assertEquals(ApiError.BadResponse, client(FakeTransport { _, _, _, _ -> ok("""{}""") }).health().errOf())
    }

    // ----- command plan -----
    @Test fun commandPlanParsesActionsAndParams() {
        val body = """{"plan":{"intent":"SET_ALARM","confidence":0.85,"actions":[{"type":"SET_ALARM","params":{"hour":7,"minute":30},"summary":"s","sensitive":true,"requiresConfirmation":true}]},"executedByBackend":false}"""
        val t = FakeTransport { _, _, _, _ -> ok(body) }
        val plan = client(t).commandPlan("set alarm for 7:30", "English").value()
        assertEquals("SET_ALARM", plan.intent)
        assertEquals(1, plan.actions.size)
        assertEquals("SET_ALARM", plan.actions[0].type)
        assertEquals(7, (plan.actions[0].params["hour"] as Number).toInt())
        assertEquals("https://raza.example.com/api/v1/command-plan", t.lastUrl)
    }

    @Test fun commandPlanWithClarificationAndNoActions() {
        val t = FakeTransport { _, _, _, _ -> ok("""{"plan":{"intent":"DIAL","actions":[],"clarification":"Please give a valid phone number."}}""") }
        val plan = client(t).commandPlan("call abc", "English").value()
        assertTrue(plan.actions.isEmpty())
        assertEquals("Please give a valid phone number.", plan.clarification)
    }

    @Test fun commandPlanMissingPlanIsBadResponse() {
        val t = FakeTransport { _, _, _, _ -> ok("""{"nope":1}""") }
        assertEquals(ApiError.BadResponse, client(t).commandPlan("x", "English").errOf())
    }

    // ----- secrets -----
    @Test fun tokenNeverAppearsInUserFacingErrors() {
        val errors = listOf(
            ApiError.Unauthorized, ApiError.Forbidden, ApiError.Offline, ApiError.Timeout,
            ApiError.Server(500), ApiError.BadResponse, ApiError.RateLimited(3), ApiError.NotConfigured,
        )
        assertTrue(errors.none { it.userMessage.contains(token) })
    }

    // ----- URL validation -----
    @Test fun urlValidator() {
        assertEquals("https://raza.example.com", UrlValidator.normalizeBase("https://raza.example.com/"))
        assertEquals("https://raza.example.com:8443", UrlValidator.normalizeBase(" https://raza.example.com:8443/api/v1 "))
        assertEquals("https://raza.example.com/base", UrlValidator.normalizeBase("https://raza.example.com/base"))
        assertNull(UrlValidator.normalizeBase("http://raza.example.com"))
        assertNull(UrlValidator.normalizeBase("https://"))
        assertNull(UrlValidator.normalizeBase("https://nodot"))
        assertNull(UrlValidator.normalizeBase("https://a.example.com:99999"))
        assertNull(UrlValidator.normalizeBase("https://a.example.com:abc"))
        assertNull(UrlValidator.normalizeBase("https://evil@host.example.com"))
        assertNull(UrlValidator.normalizeBase("https://host.example.com/a b"))
        assertNull(UrlValidator.normalizeBase(null))
    }
}
