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

    // ---- WhatsApp / YouTube / navigation / timers ----
    private val WA_NAME_BODY = Regex(
        "^(?:send\\s+)?(?:a\\s+)?whats\\s?app(?:\\s+message)?\\s+(?:to\\s+)?(.+?)\\s*(?:saying|that says|that|:)\\s+(.+)$", I
    )
    private val WA_MESSAGE_ON = Regex(
        "^(?:send\\s+)?(?:a\\s+)?message\\s+(?:to\\s+)?(.+?)\\s+on\\s+whats\\s?app\\s*(?:saying|that says|that|:)\\s+(.+)$", I
    )
    private val WA_TELL = Regex("^tell\\s+(.+?)\\s+on\\s+whats\\s?app\\s+(?:that\\s+)?(.+)$", I)
    private val WA_UR = Regex(
        "^(.+?)\\s+کو\\s+(?:واٹس\\s?ایپ|وٹس\\s?ایپ|واٹسایپ)\\s+(?:پر\\s+)?(?:میسج|پیغام|message)\\s+(?:بھیجو|کرو|بھیج دو)\\s*[:،]?\\s*(?:کہ\\s+)?(.+)$", I
    )
    private val YT_ON = Regex("^(play|watch|search|find)\\s+(.+?)\\s+(?:on|in)\\s+youtube$", I)
    private val YT_FIRST = Regex("^(?:open\\s+)?youtube\\s+(?:and\\s+)?(play|search(?:\\s+for)?|watch)\\s+(.+)$", I)
    private val YT_UR = Regex("^یوٹیوب\\s+(?:پر|میں)\\s+(.+?)\\s+(چلاؤ|چلاو|لگاؤ|لگاو|چلا دو|لگا دو|دکھاؤ|دیکھاؤ|سرچ کرو)$")
    private val TIMER = Regex(
        "^(?:set\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?|remind me in\\s+)(\\d{1,4})\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)(?:\\s+(?:to|for|that|about)\\s+(.+))?$", I
    )
    private val REMIND_AT = Regex("^remind me at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s+(?:to|that|about)\\s+(.+)$", I)

    private val NAV: List<Pair<Regex, NavKind>> = listOf(
        Regex("^(?:go back|back|press back|navigate back|واپس جاؤ|پیچھے جاؤ|واپس)$", I) to NavKind.BACK,
        Regex("^(?:go home|home|home screen|go to (?:the )?home screen|press home|ہوم|ہوم اسکرین|ہوم پر جاؤ)$", I) to NavKind.HOME,
        Regex("^(?:recent apps|show recent apps|open recent apps|recents|app switcher)$", I) to NavKind.RECENTS,
        Regex("^(?:scroll down|نیچے سکرول کرو)$", I) to NavKind.SCROLL_DOWN,
        Regex("^(?:scroll up|اوپر سکرول کرو)$", I) to NavKind.SCROLL_UP,
        Regex("^(?:next video|next reel|swipe up|skip video|skip this video|scroll to next video|اگلی ویڈیو)$", I) to NavKind.NEXT_VIDEO,
        Regex("^(?:previous video|last video|previous reel|swipe down|go to previous video|پچھلی ویڈیو)$", I) to NavKind.PREVIOUS_VIDEO,
    )

    private fun to24(hour: Int, ampm: String): Int {
        var h = hour
        if (ampm == "pm" && h < 12) h += 12
        if (ampm == "am" && h == 12) h = 0
        return h
    }

    private fun whatsApp(target: String, body: String): ParseResult {
        val who = target.trim()
        val msg = body.trim().take(MAX_WHATSAPP_BODY)
        if (who.isEmpty() || who.length > 60 || msg.isEmpty()) return ParseResult.Invalid("Tell me who to message and what to say.")
        val asNumber = Validators.normalizePhone(who)
        return ParseResult.Match(
            if (asNumber != null) DeviceAction.SendWhatsApp(null, asNumber, msg) else DeviceAction.SendWhatsApp(who, null, msg)
        )
    }

    private val SCREENS: List<Pair<List<String>, SettingsScreen>> = listOf(
        listOf("wifi", "wi-fi", "وائی فائی") to SettingsScreen.WIFI,
        listOf("bluetooth", "بلوٹوتھ") to SettingsScreen.BLUETOOTH,
        listOf("battery", "بیٹری سیور") to SettingsScreen.BATTERY,
        listOf("settings", "setting", "سیٹنگ") to SettingsScreen.MAIN,
    )

    private val PLAY_VERBS = setOf("play", "watch")
    const val MAX_WHATSAPP_BODY = 1000

    private fun youtube(query: String, autoPlay: Boolean): ParseResult {
        val q = query.trim()
        if (q.isEmpty() || q.length > 100) return ParseResult.Invalid("Tell me what to search for on YouTube (up to 100 characters).")
        return ParseResult.Match(DeviceAction.YouTubeSearch(q, autoPlay))
    }

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

        val plain = text.trimEnd('.', '!', '?', '۔', ' ')

        (WA_NAME_BODY.find(plain) ?: WA_MESSAGE_ON.find(plain) ?: WA_TELL.find(plain) ?: WA_UR.find(plain))?.let { m ->
            return whatsApp(m.groupValues[1], m.groupValues[2])
        }

        YT_ON.find(plain)?.let { m -> return youtube(m.groupValues[2], m.groupValues[1].lowercase(Locale.ROOT) in PLAY_VERBS) }
        YT_FIRST.find(plain)?.let { m -> return youtube(m.groupValues[2], m.groupValues[1].lowercase(Locale.ROOT) in PLAY_VERBS) }
        YT_UR.find(plain)?.let { m -> return youtube(m.groupValues[1], m.groupValues[2] != "سرچ کرو") }

        for ((re, kind) in NAV) if (re.matches(plain)) return ParseResult.Match(DeviceAction.Navigate(kind))

        TIMER.find(plain)?.let { m ->
            val n = m.groupValues[1].toInt()
            val unit = m.groupValues[2].lowercase(Locale.ROOT)
            val seconds = when {
                unit.startsWith("h") -> n * 3600
                unit.startsWith("m") -> n * 60
                else -> n
            }
            if (seconds !in 1..86_400) return ParseResult.Invalid("A timer must be between 1 second and 24 hours.")
            return ParseResult.Match(DeviceAction.SetTimer(seconds, m.groupValues[3].trim().take(60)))
        }

        REMIND_AT.find(plain)?.let { m ->
            val hour = to24(m.groupValues[1].toInt(), m.groupValues[3].lowercase(Locale.ROOT))
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (!Validators.validTime(hour, minute)) return ParseResult.Invalid("That reminder time is not valid.")
            return ParseResult.Match(DeviceAction.SetAlarm(hour, minute, m.groupValues[4].trim().take(60)))
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
