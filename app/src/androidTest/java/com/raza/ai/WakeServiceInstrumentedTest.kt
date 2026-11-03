package com.raza.ai

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.speech.SpeechRecognizer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.raza.ai.core.WakeState
import com.raza.ai.data.SettingsStore
import com.raza.ai.voice.WakeHub
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real Android 13 (API 33) emulator in CI. It checks what Android itself reports about the service:
 * foreground state, notification, survival after the task is removed, stop/restart, and the boot prompt.
 * Tests that need speech recognition are SKIPPED (not passed) if the emulator image has no recogniser, and the
 * "no recogniser" case is tested separately for an honest error.
 */
@RunWith(AndroidJUnit4::class)
class WakeServiceInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val settings = SettingsStore(ctx)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val recognizerAvailable get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    private fun waitFor(timeoutMs: Long = 20_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(200)
        }
        return cond()
    }

    private fun shown(id: Int) = nm.activeNotifications.any { it.id == id }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean =
        ctx.getSystemService(ActivityManager::class.java).getRunningServices(200)
            .any { it.service.className == WakeWordService::class.java.name }

    @Before
    fun clean() {
        settings.wakeEnabled = false
        WakeWordService.stop(ctx)
        waitFor(5_000) { WakeHub.state == WakeState.OFF || WakeHub.state == WakeState.ERROR }
        nm.cancelAll()
    }

    @After
    fun stopEverything() {
        settings.wakeEnabled = false
        WakeWordService.stop(ctx)
        waitFor(5_000) { !serviceRunning() }
    }

    @Test
    fun manifestDeclaresMicrophoneForegroundServiceAndPermissions() {
        val info = ctx.packageManager.getServiceInfo(ComponentName(ctx, WakeWordService::class.java), 0)
        assertTrue(
            "foregroundServiceType must include microphone",
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0,
        )
        assertEquals("service must not stop when the task is removed", 0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        val requested = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        for (p in listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        )) assertTrue("missing $p", p in requested)
    }

    @Test
    fun withoutASpeechRecognizerTheServiceReportsAnHonestErrorAndDoesNotPretendToListen() {
        assumeTrue("emulator has a recogniser; the honest-failure path does not apply", !recognizerAvailable)
        ActivityScenario.launch(MainActivity::class.java).use {
            WakeWordService.start(ctx)
            assertTrue("expected ERROR state", waitFor { WakeHub.state == WakeState.ERROR })
            assertTrue(WakeHub.error.orEmpty().contains("not available"))
            assertTrue("service must stop itself", waitFor { !serviceRunning() })
            assertTrue("user must be told it is not listening", waitFor(5_000) { shown(WakeNotifications.ID_RESUME) })
        }
    }

    @Test
    fun startedFromVisibleActivityRunsAsForegroundServiceWithNotification() {
        assumeTrue("emulator has no speech recogniser", recognizerAvailable)
        ActivityScenario.launch(MainActivity::class.java).use {
            WakeWordService.start(ctx)
            assertTrue("state should become LISTENING, was ${WakeHub.state} ${WakeHub.error}", waitFor { WakeHub.state == WakeState.LISTENING })
            assertTrue("service must be running", serviceRunning())
            assertTrue("persistent notification must be showing", waitFor(5_000) { shown(501) })
            assertTrue("ongoing", nm.activeNotifications.first { it.id == 501 }.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        }
    }

    @Test
    fun keepsListeningAfterTheAppIsRemovedFromRecents() {
        assumeTrue("emulator has no speech recogniser", recognizerAvailable)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        WakeWordService.start(ctx)
        assertTrue("never started: ${WakeHub.state} ${WakeHub.error}", waitFor { WakeHub.state == WakeState.LISTENING })
        scenario.onActivity { it.finishAndRemoveTask() } // same effect as swiping the app away
        assertTrue("activity should be gone", waitFor(10_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED })
        Thread.sleep(3_000)
        assertTrue("service must still be running", serviceRunning())
        assertEquals(WakeState.LISTENING, WakeHub.state)
        assertTrue("notification must remain", shown(501))
    }

    @Test
    fun stopAndRestartControlsWork() {
        assumeTrue("emulator has no speech recogniser", recognizerAvailable)
        ActivityScenario.launch(MainActivity::class.java).use {
            settings.wakeEnabled = true
            WakeWordService.start(ctx)
            assertTrue(waitFor { WakeHub.state == WakeState.LISTENING })

            WakeWordService.stop(ctx)
            assertTrue("state OFF after stop", waitFor { WakeHub.state == WakeState.OFF })
            assertTrue("service gone", waitFor(5_000) { !serviceRunning() })
            assertFalse("notification removed", waitFor(2_000) { false } || shown(501))
            assertFalse("a user stop must clear the wake-on flag so nothing restarts it", settings.wakeEnabled)

            WakeWordService.restart(ctx)
            assertTrue("listening again after restart, was ${WakeHub.state} ${WakeHub.error}", waitFor { WakeHub.state == WakeState.LISTENING })
            assertTrue("wake-on flag set again", settings.wakeEnabled)
            assertTrue(shown(501))
        }
    }

    @Test
    fun bootReceiverOnlyPostsAPromptAndNeverStartsTheMicrophone() {
        settings.wakeEnabled = true
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue("prompt must be posted", waitFor(5_000) { shown(WakeNotifications.ID_RESUME) })
        assertFalse("microphone service must NOT be started from boot", serviceRunning())
        assertEquals(WakeState.OFF, WakeHub.state)
        assertFalse(shown(501))

        nm.cancelAll()
        settings.wakeEnabled = false
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(1_500)
        assertFalse("no prompt when the user had listening off", shown(WakeNotifications.ID_RESUME))
    }
}
