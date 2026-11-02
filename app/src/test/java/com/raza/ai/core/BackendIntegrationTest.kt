package com.raza.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real HTTP round trips from the Android ApiClient (UrlConnectionTransport) to the real Node backend.
 *
 * Runs only when RAZA_IT_URL (for example http://127.0.0.1:18080) and RAZA_IT_TOKEN (a "user" token) are set;
 * Otherwise every test is SKIPPED (never counted as passed). CI starts the backend (no AI key) and sets them.
 *
 * ApiClient only accepts https:// addresses, as it must in production. The local test server speaks
 * plain HTTP, so this transport rewrites only the scheme, https://localhost:PORT -> http://127.0.0.1:PORT.
 */
class BackendIntegrationTest {
    private val url: String? = System.getenv("RAZA_IT_URL")
    private val userToken: String? = System.getenv("RAZA_IT_TOKEN")

    private class LocalHttp(private val realBase: String) : HttpTransport {
        private val inner = UrlConnectionTransport()
        override fun request(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResponse =
            inner.request(method, url.replace(FAKE_BASE, realBase), headers, body, timeoutMs)
    }

    private fun client(token: String = userToken.orEmpty()): ApiClient {
        assumeTrue("RAZA_IT_URL / RAZA_IT_TOKEN not set: integration test skipped", !url.isNullOrBlank() && !userToken.isNullOrBlank())
        return ApiClient({ ApiConfig(FAKE_BASE, token) }, LocalHttp(url!!.trimEnd('/')), timeoutMs = 10_000)
    }

    @Test fun healthWorksAgainstRealBackend() {
        val r = client().health()
        assertTrue("health failed: $r", r is ApiResult.Ok)
        val h = (r as ApiResult.Ok).value
        assertEquals("ok", h.status)
        assertTrue(h.version.isNotBlank())
    }

    @Test fun wrongTokenIsUnauthorized() {
        val r = client(token = "this-is-not-a-valid-token-0000").commandPlan("call 03001234567", "English")
        assertEquals(ApiResult.Err(ApiError.Unauthorized), r)
    }

    @Test fun commandPlanForCallIsSensitiveAndMapsToSafeDeviceAction() {
        val r = client().commandPlan("call 03001234567", "English")
        assertTrue("plan failed: $r", r is ApiResult.Ok)
        val plan = (r as ApiResult.Ok).value
        val dial = plan.actions.firstOrNull { it.type == "DIAL" }
        assertNotNull("backend did not plan a DIAL action: $plan", dial)
        val mapped = PlanMapper.map(dial!!)
        assertTrue("mapped=$mapped", mapped is DeviceAction.Dial)
        // The client, not the backend, decides this needs owner authentication.
        assertTrue(mapped!!.needsOwnerAuth)
    }

    @Test fun commandPlanForVolumeMaps() {
        val plan = ((client().commandPlan("volume up", "English")) as ApiResult.Ok).value
        val a = plan.actions.firstOrNull { it.type == "VOLUME" }
        assertNotNull(a)
        assertTrue(PlanMapper.map(a!!) is DeviceAction.Volume)
    }

    @Test fun emptyMessageIsRejectedByBackendValidation() {
        val r = client().assistant("   ", "English", emptyList())
        assertTrue("expected validation error, got $r", r is ApiResult.Err && r.error is ApiError.Validation)
    }

    @Test fun assistantWithoutProviderKeyReportsUnavailableAndNeverFakesAnAnswer() {
        // CI backend has no ANTHROPIC_API_KEY. A real deployment with a key would return Ok instead,
        // so only the "unconfigured" outcome is asserted when the key is absent.
        assumeTrue("RAZA_IT_NO_PROVIDER not set", System.getenv("RAZA_IT_NO_PROVIDER") == "1")
        val r = client().assistant("tell me a joke", "English", emptyList())
        assertEquals(ApiResult.Err(ApiError.ProviderUnavailable), r)
    }

    @Test fun unreachableServerIsReportedAsOffline() {
        assumeTrue(!url.isNullOrBlank())
        val c = ApiClient({ ApiConfig(FAKE_BASE, "x".repeat(20)) }, LocalHttp("http://127.0.0.1:1"), timeoutMs = 3_000)
        assertEquals(ApiResult.Err(ApiError.Offline), c.health())
    }

    companion object {
        private const val FAKE_BASE = "https://localhost:18080"
    }
}
