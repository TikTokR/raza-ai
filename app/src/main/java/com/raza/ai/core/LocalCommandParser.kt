package com.raza.ai.core

import java.util.Locale

sealed class ParseResult {
    data class Match(val action: DeviceAction) : ParseResult()
    data class Invalid(val message: String) : ParseResult()
    data object NoMatch : ParseResult()
}

/**
 * On-device command parser (English + Urdu). Works with no network at all.
 * It only PARSES: nothing here touches the phone.
 */
object LocalCommandParser {
    private val I = RegexOption.IGNORE_CASE
    private val CALL = Regex("^(?:call|dial|phone)\\s+(.+)$", I)
    private val CALL_UR = Regex("^(.+?)\\s+(?:کو\\s+)?کال\\s+کرو$")
    private val SMS = Regex(
        "^(?:send\\s+(?:an?\\s+)?(?:sms|message|text)\\s+to|text|sms)\\s+(\\+?[0-9][0-9\\s\\-()]{5,18}[0-9])\\s*(?:saying|:|that)?\\s*(.*)$", I
    )
    private val ALARM = Regex(
        "(?:set\\s+(?:an\\s+)?alarm(?:\\s+for|\\s+at)?|wake me(?:\\s+up)?\\s+at)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", I
    )
    private val VOLUME = Regex("\\bvolume\\s+(up|down)\\b|\\b(increase|decrease|raise|lower)\\s+(?:the\\s+)?volume\\b", I)
    private val MEDIA = Regex("\\b(play|pause|resume|next track|next song|previous track|stop music)\\b", I)
    private val MEDIA_CONTEXT = Regex("music|song|track|media|play$|pause$|resume$", I)
    private val TIME = Regex("\\b(what(?:'s| is) the time|what time is it|current time|time now)\\b|وقت کیا", I)
    private val DATE = Regex("\\b(what(?:'s| is) the date|today'?s date|current date|what day is it)\\b|تاریخ", I)
    private val BATTERY = Regex("\\bbattery\\b|بیٹری", I)
    private val SETTINGS_WORDS = Regex("settings|سیٹنگ", I)
    private val OPEN_SETTINGS = Regex("^(?:open|show|go to)\\b.*\\b(settings?|wifi|wi-fi|bluetooth)\\b", I)
    private val OPEN_APP = Regex("^(?:open|launch|start)\\s+(.{1,60})$", I)

    private val SCREENS: List<Pair<List<String>, SettingsScreen>> = listOf(
        listOf("wifi", "wi-fi", "وائی فائی") to SettingsScreen.WIFI,
        listOf("bluetooth", "بلوٹوتھ") to SettingsScreen.BLUETOOTH,
        listOf("battery", "بیٹری سیور") to SettingsScreen.BATTERY,
        listOf("settings", "setting", "سیٹنگ") to SettingsScreen.MAIN,
    )

    private const val BAD_NUMBER = "That doesn't look like a valid phone number. Please say or type the number again."

    fun parse(input: String): ParseResult {
        val text = input.trim()
        if (text.isEmpty()) return ParseResult.NoMatch
        val q = text.lowercase(Locale.ROOT)

        (CALL.find(text) ?: CALL_UR.find(text))?.let { m ->
            val number = Validators.normalizePhone(m.groupValues[1])
                ?: return ParseResult.Invalid(BAD_NUMBER)
            return ParseResult.Match(DeviceAction.Dial(number))
        }

        SMS.find(text)?.let { m ->
            val number = Validators.normalizePhone(m.groupValues[1])
                ?: return ParseResult.Invalid(BAD_NUMBER)
            val body = m.groupValues[2].trim().take(Validators.MAX_SMS_BODY)
            return ParseResult.Match(DeviceAction.PrepareSms(number, body))
        }

        ALARM.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            val ampm = m.groupValues[3].lowercase(Locale.ROOT)
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            if (!Validators.validTime(hour, minute)) return ParseResult.Invalid("That alarm time is not valid.")
            return ParseResult.Match(DeviceAction.SetAlarm(hour, minute))
        }

        VOLUME.find(text)?.let { m ->
            val up = m.groupValues[1].lowercase(Locale.ROOT) == "up" ||
                m.groupValues[2].lowercase(Locale.ROOT).let { it == "increase" || it == "raise" }
            return ParseResult.Match(DeviceAction.Volume(if (up) VolumeDirection.UP else VolumeDirection.DOWN))
        }

        MEDIA.find(text)?.let { m ->
            if (MEDIA_CONTEXT.containsMatchIn(q)) {
                val cmd = when (m.groupValues[1].lowercase(Locale.ROOT)) {
                    "play", "resume" -> MediaCommand.PLAY
                    "pause", "stop music" -> MediaCommand.PAUSE
                    "next track", "next song" -> MediaCommand.NEXT
                    else -> MediaCommand.PREVIOUS
                }
                return ParseResult.Match(DeviceAction.Media(cmd))
            }
        }

        if (TIME.containsMatchIn(text)) return ParseResult.Match(DeviceAction.GetTime)
        if (DATE.containsMatchIn(text)) return ParseResult.Match(DeviceAction.GetDate)
        if (BATTERY.containsMatchIn(text) && !SETTINGS_WORDS.containsMatchIn(text)) {
            return ParseResult.Match(DeviceAction.GetBattery)
        }

        if (OPEN_SETTINGS.containsMatchIn(text) || (text.contains("کھول") && SCREENS.any { (keys, _) -> keys.any { q.contains(it) } })) {
            for ((keys, screen) in SCREENS) {
                if (keys.any { q.contains(it) }) return ParseResult.Match(DeviceAction.OpenSettings(screen))
            }
        }

        OPEN_APP.find(text)?.let { m ->
            val name = Validators.cleanAppName(m.groupValues[1])
                ?: return ParseResult.Invalid("I could not understand which app to open.")
            return ParseResult.Match(DeviceAction.OpenApp(name))
        }

        return ParseResult.NoMatch
    }
}
