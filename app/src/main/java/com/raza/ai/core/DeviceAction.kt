package com.raza.ai.core

enum class VolumeDirection { UP, DOWN }
enum class MediaCommand { PLAY, PAUSE, NEXT, PREVIOUS }
enum class SettingsScreen { MAIN, WIFI, BLUETOOTH, BATTERY }

/**
 * Everything the assistant can ask the phone to do. The CLIENT decides what is
 * sensitive from the action type. A backend flag is never trusted.
 */
sealed class DeviceAction {
    /** Changes the device or contacts someone: must be confirmed before running. */
    abstract val sensitive: Boolean

    /** Extra owner check (device lock screen) on top of the YES/NO confirmation. */
    open val needsOwnerAuth: Boolean = false

    abstract fun describe(): String

    data class Dial(val number: String) : DeviceAction() {
        override val sensitive = true
        override val needsOwnerAuth = true
        override fun describe() = "Open the dialer for $number. You will press call yourself."
    }

    data class PrepareSms(val number: String, val body: String) : DeviceAction() {
        override val sensitive = true
        override val needsOwnerAuth = true
        override fun describe() = "Prepare an SMS to $number. You will press send yourself."
    }

    data class SetAlarm(val hour: Int, val minute: Int) : DeviceAction() {
        override val sensitive = true
        override fun describe() = "Create an alarm for %02d:%02d.".format(hour, minute)
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
