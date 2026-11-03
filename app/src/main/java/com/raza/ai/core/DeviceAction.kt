package com.raza.ai.core

enum class VolumeDirection { UP, DOWN }
enum class MediaCommand { PLAY, PAUSE, NEXT, PREVIOUS }
enum class NavKind { BACK, HOME, RECENTS, SCROLL_DOWN, SCROLL_UP, NEXT_VIDEO, PREVIOUS_VIDEO }
enum class SettingsScreen { MAIN, WIFI, BLUETOOTH, BATTERY }

/**
 * Everything the assistant can ask the phone to do. The CLIENT decides what is
 * sensitive from the action type. A backend flag is never trusted.
 */
sealed class DeviceAction {
    /** Changes the device or contacts someone: must be confirmed before running. */
    abstract val sensitive: Boolean

    /** Harmless, reversible actions (back, home, scroll) that run at once instead of asking YES/NO. */
    open val runsImmediately: Boolean = false

    /** Extra owner check (device lock screen) on top of the YES/NO confirmation. */
    open val needsOwnerAuth: Boolean = false

    abstract fun describe(): String

    /** [direct] = place the call now (needs the Phone permission and the "call directly" setting); otherwise only open the dialer. */
    data class Dial(val number: String, val direct: Boolean = false) : DeviceAction() {
        override val sensitive = true
        override val needsOwnerAuth = true
        override fun describe() =
            if (direct) "Place a phone call to $number now." else "Open the dialer for $number. You will press call yourself."
    }

    data class PrepareSms(val number: String, val body: String) : DeviceAction() {
        override val sensitive = true
        override val needsOwnerAuth = true
        override fun describe() = "Prepare an SMS to $number. You will press send yourself."
    }

    data class SetAlarm(val hour: Int, val minute: Int, val label: String = "") : DeviceAction() {
        override val sensitive = true
        override fun describe() =
            "Create an alarm for %02d:%02d".format(hour, minute) + (if (label.isNotBlank()) " labelled \"$label\"." else ".")
    }

    /** Countdown timer in the Clock app (used for "remind me in 10 minutes"). */
    data class SetTimer(val seconds: Int, val label: String = "") : DeviceAction() {
        override val sensitive = true
        override fun describe(): String {
            val t = when {
                seconds % 3600 == 0 -> "${seconds / 3600} hour(s)"
                seconds % 60 == 0 -> "${seconds / 60} minute(s)"
                else -> "$seconds second(s)"
            }
            return "Start a $t timer" + (if (label.isNotBlank()) " labelled \"$label\"." else ".")
        }
    }

    /**
     * [number] is null until the engine resolves [contact] from the phone's contacts; after that it is the
     * international number as digits only (what WhatsApp needs).
     */
    data class SendWhatsApp(val contact: String?, val number: String?, val body: String) : DeviceAction() {
        override val sensitive = true
        override val needsOwnerAuth = true
        override fun describe(): String {
            val who = when {
                contact != null && number != null -> "$contact (+$number)"
                contact != null -> contact
                else -> "+$number"
            }
            return "Send a WhatsApp message to $who: \"$body\". I will open the chat and send it if my accessibility service is on; otherwise you press send."
        }
    }

    data class YouTubeSearch(val query: String, val autoPlay: Boolean) : DeviceAction() {
        override val sensitive = true
        override fun describe() =
            if (autoPlay) "Search YouTube for \"$query\" and try to start the first video."
            else "Search YouTube for \"$query\"."
    }

    data class Navigate(val kind: NavKind) : DeviceAction() {
        override val sensitive = false
        override val runsImmediately = true
        override fun describe() = when (kind) {
            NavKind.BACK -> "Go back."
            NavKind.HOME -> "Go to the home screen."
            NavKind.RECENTS -> "Show recent apps."
            NavKind.SCROLL_DOWN -> "Scroll down."
            NavKind.SCROLL_UP -> "Scroll up."
            NavKind.NEXT_VIDEO -> "Swipe to the next video."
            NavKind.PREVIOUS_VIDEO -> "Swipe to the previous video."
        }
    }

    data class Volume(val direction: VolumeDirection) : DeviceAction() {
        override val sensitive = true
        override fun describe() = "Turn the volume ${direction.name.lowercase()}."
    }

    data class Media(val command: MediaCommand) : DeviceAction() {
        override val sensitive = true
        override fun describe() = "Media control: ${command.name.lowercase()}."
    }

    data class OpenSettings(val screen: SettingsScreen) : DeviceAction() {
        override val sensitive = true
        override fun describe() = "Open Android ${screen.name.lowercase()} settings."
    }

    data class OpenApp(val name: String) : DeviceAction() {
        override val sensitive = true
        override fun describe() = "Open the app \"$name\"."
    }

    data object GetTime : DeviceAction() {
        override val sensitive = false
        override fun describe() = "Read the current time."
    }

    data object GetDate : DeviceAction() {
        override val sensitive = false
        override fun describe() = "Read the current date."
    }

    data object GetBattery : DeviceAction() {
        override val sensitive = false
        override fun describe() = "Read the battery level."
    }
}
