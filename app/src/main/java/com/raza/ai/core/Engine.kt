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

/** Short rolling conversation context sent to the backend. Held in memory only. */
class ConversationHistory(private val maxItems: Int = 20) {
    private val items = ArrayDeque<HistoryItem>()

    @Synchronized
    fun addExchange(user: String, assistant: String) {
        items.addLast(HistoryItem("user", user.take(2000)))
        items.addLast(HistoryItem("assistant", assistant.take(2000)))
        while (items.size > maxItems) items.removeFirst()
        // Keep the list starting with a user turn.
        while (items.isNotEmpty() && items.first().role != "user") items.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<HistoryItem> = items.toList()

    @Synchronized
    fun clear() = items.clear()
}

interface DeviceInfo {
    fun timeText(): String
    fun dateText(): String
    fun batteryText(): String?
}

interface Connectivity {
    fun isOnline(): Boolean
}

enum class Source { LOCAL, CLOUD }

sealed class Outcome {
    data class Reply(val text: String, val source: Source) : Outcome()
    data class Confirm(val pending: ConfirmationManager.Pending) : Outcome()
    data class Failure(val text: String) : Outcome()
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

    private fun route(action: DeviceAction): Outcome {
        if (!action.sensitive) return Outcome.Reply(readOnly(action), Source.LOCAL)
        return Outcome.Confirm(confirmations.propose(action, authAvailable()))
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
