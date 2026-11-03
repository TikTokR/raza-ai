package com.raza.ai.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import com.raza.ai.core.AppMatcher
import com.raza.ai.core.DeviceAction
import com.raza.ai.core.MediaCommand
import com.raza.ai.core.SettingsScreen
import com.raza.ai.core.VolumeDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ExecResult(val ok: Boolean, val message: String)

/**
 * Runs a CONFIRMED action through official Android APIs only. It reports what Android actually
 * told us: where Android gives no confirmation (opening another app, media keys) the message says
 * "asked Android to ..." instead of claiming success.
 * Call/SMS only open the dialer or the SMS composer: the user presses call/send themselves.
 */
class ActionExecutor(private val ctx: Context) {
    private val whatsAppPackages = listOf("com.whatsapp", "com.whatsapp.w4b")
    private val youTubePackage = "com.google.android.youtube"


    fun execute(action: DeviceAction): ExecResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return run(action)
        var result = ExecResult(false, "Android did not respond in time.")
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                result = run(action)
            } catch (e: Exception) {
                result = ExecResult(false, "Android could not do that (${e.javaClass.simpleName}).")
            } finally {
                latch.countDown()
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        return result
    }

    private fun run(action: DeviceAction): ExecResult = when (action) {
        is DeviceAction.Dial -> dial(action)
        is DeviceAction.PrepareSms -> launch(
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(action.number))).putExtra("sms_body", action.body),
            "Asked Android to open a message to ${action.number}. Nothing is sent until you press send.",
        )
        is DeviceAction.SetAlarm -> launch(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, action.label.ifBlank { "RAZA AI" })
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
            "Asked the Clock app to set an alarm for %02d:%02d. It is only saved once you confirm it there.".format(action.hour, action.minute),
        )
        is DeviceAction.SetTimer -> launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, action.label.ifBlank { "RAZA AI" })
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
            "Asked the Clock app to start the timer. Check the Clock app to be sure it is running.",
        )
        is DeviceAction.SendWhatsApp -> openWhatsApp(action)
        is DeviceAction.YouTubeSearch -> openYouTube(action)
        is DeviceAction.Navigate -> ExecResult(false, "Navigation needs RAZA's accessibility service, which is not available yet.")
        is DeviceAction.Volume -> adjustVolume(action.direction)
        is DeviceAction.Media -> mediaKey(action.command)
        is DeviceAction.OpenSettings -> launch(Intent(settingsAction(action.screen)), "Asked Android to open those settings.")
        is DeviceAction.OpenApp -> openApp(action.name)
        DeviceAction.GetTime, DeviceAction.GetDate, DeviceAction.GetBattery ->
            ExecResult(false, "That is a read-only request and is answered without the executor.")
    }

    private fun dial(a: DeviceAction.Dial): ExecResult {
        val uri = Uri.parse("tel:" + Uri.encode(a.number))
        if (a.direct) {
            if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                return launch(Intent(Intent.ACTION_CALL, uri), "Asked Android to place the call to ${a.number}. Android does not tell me whether it was answered.")
            }
            val r = launch(Intent(Intent.ACTION_DIAL, uri), "")
            return if (r.ok) ExecResult(true, "I don't have the Phone permission, so I opened the dialer with ${a.number} instead. Press call yourself.") else r
        }
        return launch(Intent(Intent.ACTION_DIAL, uri), "Asked Android to open the dialer with ${a.number}. Nothing is dialled until you press call.")
    }

    private fun installed(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun openWhatsApp(a: DeviceAction.SendWhatsApp): ExecResult {
        val number = a.number ?: return ExecResult(false, "I don't have a phone number for that contact.")
        val pkg = whatsAppPackages.firstOrNull { installed(it) } ?: return ExecResult(false, "WhatsApp is not installed on this phone.")
        val uri = Uri.parse("https://wa.me/$number?text=" + Uri.encode(a.body))
        return launch(
            Intent(Intent.ACTION_VIEW, uri).setPackage(pkg),
            "Opened your WhatsApp chat with ${a.contact ?: "+$number"} with the message ready. It has NOT been sent yet: press send.",
        )
    }

    private fun openYouTube(a: DeviceAction.YouTubeSearch): ExecResult {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(a.query))
        val intent = Intent(Intent.ACTION_VIEW, uri)
        val inApp = installed(youTubePackage)
        if (inApp) intent.setPackage(youTubePackage)
        return launch(
            intent,
            "Opened YouTube search results for \"${a.query}\"" + (if (inApp) "." else " in your browser (the YouTube app is not installed).") + " Nothing is playing yet.",
        )
    }

    private fun launch(intent: Intent, okMessage: String): ExecResult = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        ExecResult(true, okMessage)
    } catch (e: ActivityNotFoundException) {
        ExecResult(false, "No app on this phone can handle that.")
    } catch (e: SecurityException) {
        ExecResult(false, "Android blocked that action.")
    }

    private fun settingsAction(screen: SettingsScreen): String = when (screen) {
        SettingsScreen.MAIN -> Settings.ACTION_SETTINGS
        SettingsScreen.WIFI -> Settings.ACTION_WIFI_SETTINGS
        SettingsScreen.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
        SettingsScreen.BATTERY -> Settings.ACTION_BATTERY_SAVER_SETTINGS
    }

    private fun adjustVolume(direction: VolumeDirection): ExecResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (direction == VolumeDirection.UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
            val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (after != before) ExecResult(true, "Media volume changed from $before to $after (maximum $max).")
            else ExecResult(false, "The volume did not change. It may already be at its limit, or this phone blocked it.")
        } catch (e: SecurityException) {
            ExecResult(false, "Android blocked the volume change.")
        }
    }

    private fun mediaKey(command: MediaCommand): ExecResult {
        val code = when (command) {
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ExecResult(true, "Sent the ${command.name.lowercase()} media key. Android does not tell me whether a player responded.")
    }

    private fun openApp(name: String): ExecResult {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val labels = apps.map { it.loadLabel(pm).toString() }
        val idx = AppMatcher.best(name, labels)
            ?: return ExecResult(false, "I couldn't find an installed app called \"$name\".")
        val pkg = apps[idx].activityInfo.packageName
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: return ExecResult(false, "Android gave no way to launch ${labels[idx]}.")
        return launch(intent, "Asked Android to open ${labels[idx]}.")
    }
}
