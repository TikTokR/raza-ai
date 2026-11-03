package com.raza.ai.core

/** Re-validates a backend-suggested action. The backend is never trusted blindly. */
object PlanMapper {
    fun map(remote: RemoteAction): DeviceAction? {
        val p = remote.params
        return when (remote.type) {
            "DIAL" -> Validators.normalizePhone(p["number"] as? String)?.let { DeviceAction.Dial(it) }
            "PREPARE_SMS" -> Validators.normalizePhone(p["number"] as? String)?.let {
                DeviceAction.PrepareSms(it, ((p["body"] as? String) ?: "").take(Validators.MAX_SMS_BODY))
            }
            "SET_ALARM" -> {
                val h = (p["hour"] as? Number)?.toInt()
                val m = (p["minute"] as? Number)?.toInt()
                if (h != null && m != null && Validators.validTime(h, m)) DeviceAction.SetAlarm(h, m) else null
            }
            "VOLUME" -> when (p["direction"]) {
                "UP" -> DeviceAction.Volume(VolumeDirection.UP)
                "DOWN" -> DeviceAction.Volume(VolumeDirection.DOWN)
                else -> null
            }
            "MEDIA" -> MediaCommand.entries.firstOrNull { it.name == p["command"] }?.let { DeviceAction.Media(it) }
            "OPEN_SETTINGS" -> SettingsScreen.entries.firstOrNull { it.name == p["screen"] }?.let { DeviceAction.OpenSettings(it) }
            "OPEN_APP" -> Validators.cleanAppName(p["name"] as? String)?.let { DeviceAction.OpenApp(it) }
            "GET_TIME" -> DeviceAction.GetTime
            "GET_DATE" -> DeviceAction.GetDate
            "GET_BATTERY" -> DeviceAction.GetBattery
            else -> null
        }
    }
}

/**
 * Short rolling conversation context sent to the backend. [preamble] adds a fixed opening pair
 * (the user's saved notes). The total never exceeds [maxItems], which matches the backend limit.
 */
class ConversationHistory(
    private val maxItems: Int = 20,
    private val preamble: () -> List<HistoryItem> = { emptyList() },
) {
    private val items = ArrayDeque<HistoryItem>()

    @Synchronized
    fun addExchange(user: String, assistant: String) {
        items.addLast(HistoryItem("user", user.take(2000)))
        items.addLast(HistoryItem("assistant", assistant.take(2000)))
        while (items.size > maxItems) items.removeFirst()
        // Keep the list starting with a user turn.
        while (items.isNotEmpty() && items.first().role != "user") items.removeFirst()
    }

    /** Loads previously saved turns (oldest first). Anything that breaks user/assistant order is dropped. */
    @Synchronized
    fun restore(saved: List<HistoryItem>) {
        items.clear()
        var expectUser = true
        for (h in saved) {
            val want = if (expectUser) "user" else "assistant"
            if (h.role == want) {
                items.addLast(h)
                expectUser = !expectUser
            }
        }
        if (items.isNotEmpty() && items.last().role == "user") items.removeLast()
        while (items.size > maxItems) items.removeFirst()
        while (items.isNotEmpty() && items.first().role != "user") items.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<HistoryItem> {
        val pre = preamble().take(2)
        var body = items.toList().takeLast(maxItems - pre.size)
        while (body.isNotEmpty() && body.first().role != "user") body = body.drop(1)
        return pre + body
    }

    @Synchronized
    fun clear() = items.clear()
}

interface DeviceInfo {
    fun timeText(): String
    fun dateText(): String
    fun batteryText(): String?
}

fun interface Connectivity {
    fun isOnline(): Boolean
}

enum class Source { LOCAL, CLOUD }

sealed class Outcome {
    data class Reply(val text: String, val source: Source) : Outcome()
    data class Confirm(val pending: ConfirmationManager.Pending) : Outcome()
    data class Failure(val text: String) : Outcome()

    /** A harmless action (back, home, scroll) to run right now with no YES/NO. */
    data class Run(val action: DeviceAction) : Outcome()
}

/**
 * Decides what to do with one user message:
 *  1. on-device parser (works offline)
 *  2. if online: backend command-plan, then backend conversation
 * Sensitive actions are never executed here: they come back as Confirm.
 */
class AssistantEngine(
    private val api: ApiOps,
    private val confirmations: ConfirmationManager,
    private val connectivity: Connectivity,
    private val deviceInfo: DeviceInfo,
    private val history: ConversationHistory,
    private val authAvailable: () -> Boolean,
    private val contacts: ContactResolver = ContactResolver { ContactResult.NoPermission },
    private val defaultCountryCode: () -> String = { "" },
    private val directCalls: () -> Boolean = { false },
) {
    fun handle(message: String, language: String): Outcome {
        val text = message.trim()
        if (text.isEmpty()) return Outcome.Failure("I didn't catch anything. Please try again.")

        when (val parsed = LocalCommandParser.parse(text)) {
            is ParseResult.Match -> return route(parsed.action)
            is ParseResult.Invalid -> return Outcome.Reply(parsed.message, Source.LOCAL)
            ParseResult.NoMatch -> Unit
        }

        if (!connectivity.isOnline()) {
            return Outcome.Reply(OFFLINE_NOTICE, Source.LOCAL)
        }

        when (val plan = api.commandPlan(text, language)) {
            is ApiResult.Err -> return Outcome.Failure(plan.error.userMessage)
            is ApiResult.Ok -> {
                val action = plan.value.actions.firstNotNullOfOrNull { PlanMapper.map(it) }
                if (action != null) return route(action)
                val clarification = plan.value.clarification
                if (plan.value.actions.isEmpty() && clarification != null) {
                    return Outcome.Reply(clarification, Source.CLOUD)
                }
            }
        }

        return when (val r = api.assistant(text, language, history.snapshot())) {
            is ApiResult.Err -> Outcome.Failure(r.error.userMessage)
            is ApiResult.Ok -> {
                history.addExchange(text, r.value)
                Outcome.Reply(r.value, Source.CLOUD)
            }
        }
    }

    private fun route(action: DeviceAction): Outcome = when {
        action is DeviceAction.SendWhatsApp -> routeWhatsApp(action)
        action is DeviceAction.Dial -> propose(action.copy(direct = directCalls()))
        !action.sensitive && action.runsImmediately -> Outcome.Run(action)
        !action.sensitive -> Outcome.Reply(readOnly(action), Source.LOCAL)
        else -> propose(action)
    }

    private fun propose(action: DeviceAction): Outcome = Outcome.Confirm(confirmations.propose(action, authAvailable()))

    /** Finds the number for a spoken name. It never guesses between different people. */
    private fun routeWhatsApp(action: DeviceAction.SendWhatsApp): Outcome {
        var name = action.contact
        var raw = action.number
        if (raw == null) {
            val query = name ?: return Outcome.Reply("Who should I message on WhatsApp?", Source.LOCAL)
            when (val r = contacts.resolve(query)) {
                is ContactResult.Found -> { name = r.displayName; raw = r.number }
                ContactResult.NotFound -> return Outcome.Reply(
                    "I couldn't find a contact called \"$query\". Say the contact's saved name, or give the phone number.", Source.LOCAL)
                is ContactResult.Ambiguous -> return Outcome.Reply(
                    "I found several matches: ${r.options.joinToString("; ")}. Say the full name or give the number, and I won't guess.", Source.LOCAL)
                ContactResult.NoPermission -> return Outcome.Reply(
                    "I need permission to read your contacts to find \"$query\". Allow Contacts in Settings > Permissions, or give me the phone number.", Source.LOCAL)
            }
        }
        val digits = WhatsAppNumber.normalize(raw, defaultCountryCode())
            ?: return Outcome.Reply(
                "I can't tell the country for ${name ?: raw}'s number ($raw). Set \"Default country code\" in Settings, or give the number starting with +.",
                Source.LOCAL,
            )
        return propose(DeviceAction.SendWhatsApp(name, digits, action.body))
    }

    private fun readOnly(action: DeviceAction): String = when (action) {
        DeviceAction.GetTime -> "It is ${deviceInfo.timeText()}."
        DeviceAction.GetDate -> "Today is ${deviceInfo.dateText()}."
        DeviceAction.GetBattery -> deviceInfo.batteryText()?.let { "Battery is at $it." }
            ?: "I can't read the battery level on this device."
        else -> "Unsupported."
    }

    companion object {
        const val OFFLINE_NOTICE =
            "You're offline, so I can only run on-device commands: time, date, battery, opening apps and " +
                "settings, alarms, preparing calls and texts, volume and media. Connect to the internet for conversation."
    }
}
