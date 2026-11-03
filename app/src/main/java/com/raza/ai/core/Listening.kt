package com.raza.ai.core

enum class Severity { OK, WARN, BAD, OFF }

/**
 * What the app may truthfully say about background listening, and which controls make sense.
 * [micIsOn] is true ONLY while the service is really capturing audio; a switch the user left "on" never counts.
 */
data class ListenStatus(
    val label: String,
    val detail: String,
    val severity: Severity,
    val micIsOn: Boolean,
    val canStart: Boolean,
    val canStop: Boolean,
    val canRestart: Boolean,
)

object ListeningStatusResolver {
    /**
     * @param wantsOn the user asked for listening to be on (persisted); the service may have died since.
     * @param state what the running service reports. After a process death this is OFF even if [wantsOn].
     * @param stopReason why Android last ended the app (from ApplicationExitInfo), if known.
     */
    fun resolve(
        micGranted: Boolean,
        notificationsGranted: Boolean,
        wantsOn: Boolean,
        state: WakeState,
        error: String?,
        phrase: String,
        stopReason: String?,
    ): ListenStatus {
        if (!micGranted) {
            return ListenStatus(
                "MICROPHONE PERMISSION NEEDED",
                "Allow the microphone for RAZA AI, then tap Start. Until then nothing is listening.",
                Severity.BAD, micIsOn = false, canStart = true, canStop = wantsOn, canRestart = false,
            )
        }
        return when (state) {
            WakeState.LISTENING -> ListenStatus(
                "LISTENING FOR \"$phrase\"",
                if (notificationsGranted) "The microphone is on, in the background, and the notification is showing."
                else "The microphone is on, but notifications are blocked, so Android may hide the \"microphone is on\" notification.",
                if (notificationsGranted) Severity.OK else Severity.WARN,
                micIsOn = true, canStart = false, canStop = true, canRestart = true,
            )
            WakeState.STARTING -> ListenStatus(
                "STARTING...", "Waiting for Android to start the listener. Nothing is listening yet.",
                Severity.WARN, micIsOn = false, canStart = false, canStop = true, canRestart = true,
            )
            WakeState.COMMAND_LISTENING -> ListenStatus(
                "HEARD YOU: LISTENING TO YOUR COMMAND", "The wake phrase was detected. Listening for what you want.",
                Severity.OK, micIsOn = true, canStart = false, canStop = true, canRestart = true,
            )
            WakeState.PAUSED -> ListenStatus(
                "PAUSED: MICROPHONE OFF", "Listening is paused. Resume it from the notification or the Pause/Resume button.",
                Severity.WARN, micIsOn = false, canStart = false, canStop = true, canRestart = true,
            )
            WakeState.ERROR -> ListenStatus(
                "STOPPED: ${error ?: "error"}", "The listener stopped because of an error. Nothing is listening. Fix the problem, then tap Restart.",
                Severity.BAD, micIsOn = false, canStart = true, canStop = wantsOn, canRestart = true,
            )
            WakeState.OFF -> if (wantsOn) {
                ListenStatus(
                    "STOPPED (NOT LISTENING)",
                    "Listening was on, but the background service is no longer running. " +
                        (stopReason?.let { "Last time Android ended RAZA: $it " } ?: "Android may have ended RAZA in the background, or the phone restarted. ") +
                        "Tap Restart to listen again.",
                    Severity.BAD, micIsOn = false, canStart = true, canStop = true, canRestart = true,
                )
            } else {
                ListenStatus(
                    "OFF", "Not listening. Tap Start to listen for \"$phrase\" in the background.",
                    Severity.OFF, micIsOn = false, canStart = true, canStop = false, canRestart = false,
                )
            }
        }
    }
}

/** Plain-language text for android.app.ApplicationExitInfo reasons (as ints, to stay JVM-testable). */
object ExitReasons {
    fun describe(reason: Int): String = when (reason) {
        1 -> "RAZA stopped itself."
        2 -> "A kill signal was sent to RAZA by Android or another app."
        3 -> "Android ended RAZA to free up memory."
        4, 5 -> "RAZA crashed."
        6 -> "RAZA stopped responding and was closed."
        7 -> "RAZA failed to start."
        8 -> "A permission was changed, which makes Android restart the app."
        9 -> "Android ended RAZA for using too many resources."
        10 -> "RAZA was force-stopped (from Settings, a battery manager or a cleaner app)."
        11 -> "RAZA was stopped because of a user switch or stop."
        12 -> "RAZA stopped because something it depends on stopped."
        13 -> "Android gave no reason."
        14 -> "Android froze RAZA to save battery."
        15 -> "RAZA was disabled or changed in Settings."
        16 -> "RAZA was updated or reinstalled."
        else -> "Unknown reason ($reason)."
    }
}
