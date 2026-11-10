package com.vdx.sonic.voice

import com.vdx.sonic.IntentMode
import com.vdx.sonic.IntentType
import com.vdx.sonic.SonicIntent

/**
 * IntentParser — V1-capability natural language → SonicIntent (voice sessions).
 */
class IntentParser {

    companion object {
        private const val HIGH = 0.95f
        private const val MED = 0.82f
        private const val LOW = 0.6f

        // Voice-flow abort phrases (voice-flow abort). A pure cancel
        // utterance ("stop", "never mind", "dismiss", "cancel") aborts the in-flight
        // command before any action runs. Bare "stop" is matched separately in
        // parseRegex so a command-word "stop X" still parses normally.
        val CANCEL_PHRASES = setOf("cancel", "never mind", "nevermind", "dismiss")
    }

    fun parse(cleanedText: String, useLlm: Boolean = false): SonicIntent {
        val text = cleanedText.trim()
        if (text.isBlank()) {
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.UNKNOWN,
                rawText = text,
                confidence = 0f,
                clarificationNeeded = true,
                clarificationQuestion = "I didn't catch that. Please say it again."
            )
        }

        val result = parseRegex(text)
        if (result != null && result.confidence >= MED) return result
        if (result != null) {
            return result.copy(clarificationNeeded = true)
        }

        return SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.UNKNOWN,
            rawText = text,
            confidence = LOW,
            clarificationNeeded = true,
            clarificationQuestion = "Try: WhatsApp, Uber, YouTube, Gmail, call, SMS, settings, or search."
        )
    }

    private fun parseRegex(text: String): SonicIntent? {
        val t = text.lowercase().trim()

        // CANCEL — first-class voice-flow abort (voice-flow abort).
        // A pure abort utterance cancels the in-flight command/plan before any action
        // runs, instead of being swallowed as a substring of a longer command. Guarded
        // so a longer "cancel my Uber ride" still routes to BOOK_RIDE, not an abort.
        if (t in CANCEL_PHRASES) {
            return SonicIntent(IntentMode.COMMAND, IntentType.CANCEL, rawText = text, confidence = HIGH)
        }
        // "stop" alone is ambiguous vs "stop reading"/"stop listening" → treat bare
        // "stop" as an abort; command-word "stop X" flows through normal parsing.
        if (t == "stop") {
            return SonicIntent(IntentMode.COMMAND, IntentType.CANCEL, rawText = text, confidence = HIGH)
        }

        // System toggles / queries first (specific)
        Regex("""(?:turn|switch)\s+(on|off)\s+(wifi|wi-?fi|bluetooth|mobile data|flashlight|torch)""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.SYSTEM_TOGGLE, rawText = text, confidence = HIGH,
                entities = mapOf("state" to m.groupValues[1], "target" to m.groupValues[2])
            )
        }
        Regex("""(?:enable|disable)\s+(wifi|wi-?fi|bluetooth|mobile data|flashlight)""").find(t)?.let { m ->
            val state = if (t.contains("disable")) "off" else "on"
            return SonicIntent(
                IntentMode.COMMAND, IntentType.SYSTEM_TOGGLE, rawText = text, confidence = HIGH,
                entities = mapOf("state" to state, "target" to m.groupValues[1])
            )
        }
        if (t.contains("silent mode") || t == "silence phone" || t.contains("do not disturb")) {
            return SonicIntent(
                IntentMode.COMMAND, IntentType.SYSTEM_TOGGLE, rawText = text, confidence = HIGH,
                entities = mapOf("target" to "silent", "state" to "on")
            )
        }
        if (Regex("""what(?:'s| is)?\s+(?:my\s+)?battery|battery\s+(?:level|percent)""").containsMatchIn(t)) {
            return SonicIntent(IntentMode.SYSTEM_QUERY, IntentType.SYSTEM_QUERY, rawText = text, confidence = HIGH)
        }
        if (Regex("""what(?:'s| is)?\s+the\s+(?:time|date)|what time is it""").containsMatchIn(t)) {
            return SonicIntent(IntentMode.SYSTEM_QUERY, IntentType.SYSTEM_QUERY, rawText = text, confidence = HIGH)
        }
        Regex("""set\s+(?:an?\s+)?alarm\s+(?:for\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(t)?.let { m ->
            var hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val ampm = m.groupValues.getOrNull(3).orEmpty()
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            return SonicIntent(
                IntentMode.COMMAND, IntentType.SET_ALARM, rawText = text, confidence = HIGH,
                entities = mapOf("hour" to hour.toString(), "minute" to minute.toString())
            )
        }
        if (t.contains("screenshot") || t.contains("take a screen shot")) {
            return SonicIntent(
                IntentMode.COMMAND, IntentType.SYSTEM_TOGGLE, rawText = text, confidence = MED,
                entities = mapOf("target" to "screenshot")
            )
        }

        // CALL — but "call X on WhatsApp" is a WhatsApp VOICE call, not a phone
        // call to a contact named "Mom on WhatsApp". Must run BEFORE the generic
        // call regex, which would otherwise swallow the whole "mom on whatsapp"
        // as a single contact name (misrouted → confirmation dead-end).
        Regex("""(?:please\s+)?(?:call|phone|ring|dial)\s+(?:on\s+|via\s+)?whats?app\s+(.+)$""").find(t)?.let { m ->
            return wa(text, title(m.groupValues[1]), "", "call")
        }
        Regex("""(?:please\s+)?(?:call|phone|ring|dial)\s+(.+?)\s+on\s+whats?app$""").find(t)?.let { m ->
            return wa(text, title(m.groupValues[1]), "", "call")
        }
        Regex("""^(?:please\s+)?(?:call|phone|ring|dial)\s+(.+)$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.CALL, rawText = text, confidence = HIGH,
                entities = mapOf("contact" to title(m.groupValues[1]), "action" to "call"),
                requiresConfirmation = true
            )
        }

        // CONTACTS
        Regex("""(?:save|add|create)\s+(?:a\s+)?contact\s+(.+)$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.CONTACT_MANAGE, rawText = text, confidence = HIGH,
                entities = mapOf("action" to "create", "contact" to title(m.groupValues[1]))
            )
        }
        Regex("""(?:find|search|lookup)\s+contact\s+(.+)$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.CONTACT_MANAGE, rawText = text, confidence = HIGH,
                entities = mapOf("action" to "search", "contact" to title(m.groupValues[1]))
            )
        }
        Regex("""(?:delete|block|edit)\s+contact\s+(.+)$""").find(t)?.let { m ->
            val action = when {
                t.startsWith("delete") -> "delete"
                t.startsWith("block") -> "block"
                else -> "edit"
            }
            return SonicIntent(
                mode = IntentMode.COMMAND,
                type = IntentType.CONTACT_MANAGE,
                rawText = text,
                confidence = HIGH,
                entities = mapOf("action" to action, "contact" to title(m.groupValues[1])),
                requiresConfirmation = true
            )
        }

        // WHATSAPP deep — prefer explicit "on WhatsApp" / leading WhatsApp forms
        Regex("""whats?\s?app\s+(?:voice\s+)?call\s+(.+)$""").find(t)?.let { m ->
            return wa(text, title(m.groupValues[1]), "", "call")
        }
        Regex("""whats?\s?app\s+video\s+call\s+(.+)$""").find(t)?.let { m ->
            return wa(text, title(m.groupValues[1]), "", "video_call")
        }
        Regex("""(?:reply|forward|delete|block)\s+(?:on\s+)?whats?\s?app(?:\s+(?:to|for|with)\s+(.+))?$""").find(t)?.let { m ->
            val action = when {
                t.contains("reply") -> "reply"
                t.contains("forward") -> "forward"
                t.contains("delete") -> "delete"
                else -> "block"
            }
            return wa(text, title(m.groupValues.getOrNull(1).orEmpty()), "", action)
        }
        Regex("""share\s+location\s+(?:on\s+)?whats?\s?app(?:\s+(?:to|with)\s+(.+))?$""").find(t)?.let { m ->
            return wa(text, title(m.groupValues.getOrNull(1).orEmpty()), "", "location")
        }
        // "message Ravi on WhatsApp that …"
        Regex(
            """(?:message|text|send(?:\s+a)?\s+message\s+to)\s+(.+?)\s+on\s+whats\s?app(?:\s+(?:saying|that|:)\s+(.+))?$"""
        ).find(t)?.let { m ->
            return wa(text, title(m.groupValues[1]), m.groupValues.getOrNull(2).orEmpty(), "send")
        }
        // "send WhatsApp to Ravi [saying …]" / "send it on WhatsApp to mom"
        // Natural spoken form (Cody): "send whatsapp to mom", "send it on whatsapp to dad".
        // Contact = first token after "to" (single word, most common); if a
        // delimiter (saying/that/message/to say/:/then) is present, everything
        // after it is the message; otherwise the rest of the phrase is the message.
        Regex(
            """(?:send|start)\s+(?:it\s+)?(?:on\s+)?whats?app\s+(?:to\s+)?(\w+)(?:\s+(?:saying|that|message|to say|:)\s+(.+))?(?:.*)$"""
        ).find(t)?.let { m ->
            val contact = m.groupValues[1].trim()
            val message = if (m.groupValues.getOrNull(2).isNullOrBlank()) {
                // No explicit delimiter → remainder after the contact is the message.
                val after = t.replaceFirst(Regex("""(?:send|start)\s+(?:it\s+)?(?:on\s+)?whats?app\s+(?:to\s+)?\w+"""), "").trim()
                after.removePrefix(",").trim()
            } else m.groupValues[2].trim()
            return wa(text, title(contact), message, "send")
        }
        // "whatsapp Ravi saying …" / "whatsapp to Ravi that …"
        Regex(
            """whats\s?app\s+(?:to\s+)?(.+?)(?:\s+(?:saying|that|message|:)\s+(.+))?$"""
        ).find(t)?.let { m ->
            return wa(text, title(m.groupValues[1]), m.groupValues.getOrNull(2).orEmpty(), "send")
        }
        // Default "message X that …" → WhatsApp (regional default)
        Regex("""(?:message|text)\s+(.+?)(?:\s+(?:saying|that|:)\s+(.+))?$""").find(t)?.let { m ->
            return wa(text, title(m.groupValues[1]), m.groupValues.getOrNull(2).orEmpty(), "send", MED)
        }

        // SMS
        Regex("""(?:read|browse)\s+(?:my\s+)?(?:sms|texts|messages)$""").find(t)?.let {
            return SonicIntent(
                IntentMode.READ, IntentType.SMS, rawText = text, confidence = HIGH,
                entities = mapOf("action" to "read")
            )
        }
        Regex("""(?:sms|send\s+sms\s+to|text\s+message\s+to)\s+(.+?)(?:\s+(?:saying|that|:)\s+(.+))?$""").find(t)?.let { m ->
            val msg = m.groupValues.getOrNull(2).orEmpty().trim()
            return SonicIntent(
                IntentMode.COMMAND, IntentType.SMS, rawText = text, confidence = HIGH,
                entities = mapOf(
                    "contact" to title(m.groupValues[1]),
                    "message" to msg,
                    "action" to "send"
                ),
                requiresConfirmation = msg.isNotBlank()
            )
        }

        // GMAIL / EMAIL
        Regex("""(?:read|open)\s+(?:my\s+)?(?:email|gmail|mail|inbox)$""").find(t)?.let {
            return SonicIntent(
                IntentMode.READ, IntentType.EMAIL, rawText = text, confidence = HIGH,
                entities = mapOf("action" to "read"), targetApp = "com.google.android.gm"
            )
        }
        Regex("""(?:reply|reply all|forward|delete|star|block)\s+(?:this\s+)?(?:email|mail)$""").find(t)?.let {
            val action = when {
                t.contains("reply all") -> "reply_all"
                t.startsWith("reply") -> "reply"
                t.startsWith("forward") -> "forward"
                t.startsWith("delete") -> "delete"
                t.startsWith("star") -> "star"
                else -> "block"
            }
            return SonicIntent(
                IntentMode.COMMAND, IntentType.EMAIL, rawText = text, confidence = HIGH,
                entities = mapOf("action" to action), requiresConfirmation = action == "delete" || action == "block"
            )
        }
        Regex("""(?:email|mail|send\s+email\s+to)\s+(.+?)(?:\s+(?:saying|that|about|:)\s+(.+))?$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.EMAIL, rawText = text, confidence = HIGH,
                entities = mapOf(
                    "contact" to title(m.groupValues[1]),
                    "message" to m.groupValues.getOrNull(2).orEmpty().trim(),
                    "action" to "compose"
                ),
                requiresConfirmation = true
            )
        }

        // UBER
        Regex("""(?:cancel|edit|share)\s+(?:my\s+)?(?:uber|ride|trip)$""").find(t)?.let {
            val action = when {
                t.contains("cancel") -> "cancel"
                t.contains("edit") -> "edit"
                else -> "share"
            }
            return SonicIntent(
                IntentMode.COMMAND, IntentType.BOOK_RIDE, rawText = text, confidence = HIGH,
                entities = mapOf("action" to action), requiresConfirmation = action == "cancel"
            )
        }
        Regex("""(?:message|call)\s+(?:the\s+)?driver$""").find(t)?.let {
            val action = if (t.contains("call")) "call_driver" else "message_driver"
            return SonicIntent(
                IntentMode.COMMAND, IntentType.BOOK_RIDE, rawText = text, confidence = HIGH,
                entities = mapOf("action" to action)
            )
        }
        Regex(
            """(?:(?:book|get|order)\s+(?:me\s+)?(?:an?\s+)?)?(?:uber|ola|ride)\s+(?:to\s+|for\s+)?(.+)$"""
        ).find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.BOOK_RIDE, rawText = text, confidence = HIGH,
                targetApp = "com.ubercab",
                entities = mapOf(
                    "destination" to title(m.groupValues[1].removePrefix("to ").removePrefix("the ")),
                    "action" to "book"
                ),
                requiresConfirmation = true
            )
        }
        Regex("""(?:book|get)\s+(?:me\s+)?(?:an?\s+)?(?:uber|ride)(?:\s+home)?$""").find(t)?.let {
            return SonicIntent(
                IntentMode.COMMAND, IntentType.BOOK_RIDE, rawText = text, confidence = MED,
                entities = mapOf("destination" to "home", "action" to "book"),
                requiresConfirmation = true
            )
        }

        // YOUTUBE
        Regex("""(?:like|subscribe|share)\s+(?:this\s+)?(?:video|youtube)?$""").find(t)?.let {
            val action = when {
                t.contains("like") -> "like"
                t.contains("subscribe") -> "subscribe"
                else -> "share"
            }
            return SonicIntent(
                IntentMode.COMMAND, IntentType.YOUTUBE_CONTROL, rawText = text, confidence = HIGH,
                entities = mapOf("action" to action)
            )
        }
        Regex("""comment\s+(?:on\s+)?(?:this\s+)?(?:video\s+)?(?:saying\s+)?(.+)$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.YOUTUBE_CONTROL, rawText = text, confidence = HIGH,
                entities = mapOf("action" to "comment", "text" to m.groupValues[1].trim())
            )
        }
        Regex("""(?:forward|skip|rewind)\s+(\d+)\s+seconds?""").find(t)?.let { m ->
            val action = if (t.contains("rewin")) "rewind" else "forward"
            return SonicIntent(
                IntentMode.COMMAND, IntentType.YOUTUBE_CONTROL, rawText = text, confidence = HIGH,
                entities = mapOf("action" to action, "seconds" to m.groupValues[1])
            )
        }
        Regex("""(?:open\s+)?youtube(?:\s+and)?\s+(?:play|search(?:\s+for)?)\s+(.+)$""").find(t)?.let { m ->
            return yt(text, m.groupValues[1])
        }
        Regex("""play\s+(.+?)\s+on\s+youtube$""").find(t)?.let { m -> return yt(text, m.groupValues[1]) }
        Regex("""(?:search\s+)?youtube\s+(?:for\s+)?(.+)$""").find(t)?.let { m -> return yt(text, m.groupValues[1]) }

        // PLAY STORE
        Regex("""(?:install|update|uninstall)\s+(.+?)(?:\s+from\s+play\s+store)?$""").find(t)?.let { m ->
            val action = when {
                t.startsWith("install") -> "install"
                t.startsWith("update") -> "update"
                else -> "uninstall"
            }
            return SonicIntent(
                IntentMode.COMMAND, IntentType.PLAY_STORE, rawText = text, confidence = HIGH,
                entities = mapOf("action" to action, "query" to m.groupValues[1].trim()),
                requiresConfirmation = action != "update"
            )
        }
        Regex("""(?:search\s+)?play\s+store\s+(?:for\s+)?(.+)$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.PLAY_STORE, rawText = text, confidence = HIGH,
                entities = mapOf("action" to "search", "query" to m.groupValues[1].trim())
            )
        }

        // PDF / IMAGE
        if (t.contains("read") && t.contains("pdf")) {
            return SonicIntent(IntentMode.READ, IntentType.READ_PDF, rawText = text, confidence = MED)
        }
        if (t.contains("describe") && (t.contains("image") || t.contains("picture") || t.contains("photo") || t.contains("screen"))) {
            return SonicIntent(IntentMode.READ, IntentType.DESCRIBE_IMAGE, rawText = text, confidence = MED)
        }

        // OPEN APP — settings sections are not apps
        Regex("""^(?:please\s+)?(?:open|launch|start|switch to)\s+(.+)$""").find(t)?.let { m ->
            var app = m.groupValues[1].trim().replace(Regex("""\s+and\s+.*$"""), "")
            if (app.endsWith(" settings") || app == "settings") {
                val section = app.removeSuffix(" settings").trim().ifBlank { "settings" }
                return SonicIntent(
                    IntentMode.NAVIGATION, IntentType.SETTINGS_NAVIGATION, rawText = text, confidence = HIGH,
                    entities = mapOf("section" to section)
                )
            }
            return SonicIntent(
                IntentMode.COMMAND, IntentType.APP_LAUNCH, rawText = text, confidence = HIGH,
                entities = mapOf("app" to app, "app_name" to app)
            )
        }

        if (t == "go back" || t == "back") {
            return SonicIntent(IntentMode.COMMAND, IntentType.GO_BACK, rawText = text, confidence = HIGH)
        }
        if (t == "go home" || t == "home screen" || t == "home") {
            return SonicIntent(IntentMode.COMMAND, IntentType.GO_HOME, rawText = text, confidence = HIGH)
        }

        // GESTURES — tap / scroll (task-spec grammar: 'tap', 'scroll down', 'read screen', 'go back')
        Regex("""^(?:please\s+)?(?:tap|click|press)\s+(?:on\s+)?(.+)$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.GESTURE, rawText = text, confidence = HIGH,
                entities = mapOf("action" to "tap", "target" to m.groupValues[1].trim())
            )
        }
        if (t == "tap" || t == "click" || t == "press") {
            return SonicIntent(
                IntentMode.COMMAND, IntentType.GESTURE, rawText = text, confidence = MED,
                entities = mapOf("action" to "tap")
            )
        }
        Regex("""^(?:please\s+)?scroll\s+(down|up|left|right)$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.GESTURE, rawText = text, confidence = HIGH,
                entities = mapOf("action" to "scroll", "direction" to m.groupValues[1])
            )
        }
        if (t.contains("scroll") && (t.contains("down") || t.contains("up") || t.contains("left") || t.contains("right"))) {
            val dir = when {
                t.contains("down") -> "down"
                t.contains("up") -> "up"
                t.contains("left") -> "left"
                else -> "right"
            }
            return SonicIntent(
                IntentMode.COMMAND, IntentType.GESTURE, rawText = text, confidence = MED,
                entities = mapOf("action" to "scroll", "direction" to dir)
            )
        }

        if (t.contains("read") && (t.contains("screen") || t.contains("this") || t.contains("page") || t.contains("aloud"))) {
            return SonicIntent(IntentMode.READ, IntentType.READ_SCREEN, rawText = text, confidence = HIGH)
        }
        if (t.contains("notification")) {
            return SonicIntent(IntentMode.READ, IntentType.READ_NOTIFICATIONS, rawText = text, confidence = MED)
        }

        Regex("""^(?:search|google|find)\s+(?:for\s+|the\s+web\s+for\s+)?(.+)$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.SEARCH, rawText = text, confidence = HIGH,
                entities = mapOf("query" to m.groupValues[1].trim())
            )
        }

        Regex("""(?:open|go to)\s+(.+)\s+settings$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.NAVIGATION, IntentType.SETTINGS_NAVIGATION, rawText = text, confidence = MED,
                entities = mapOf("section" to m.groupValues[1].trim())
            )
        }

        Regex("""(?:remember|save)\s+(?:that\s+)?(.+)$""").find(t)?.let { m ->
            return SonicIntent(
                IntentMode.COMMAND, IntentType.MEMORY_STORE, rawText = text, confidence = HIGH,
                entities = mapOf("value" to m.groupValues[1].trim())
            )
        }

        // DRAFT_NOTE — safe action: prepare & save a project note / draft. Requires confirmation.
        // Body may be introduced by about/on/for/that/: or be the trailing text after "note".
        Regex("""(?:draft|write|save)\s+(?:a\s+)?(?:project\s+)?note(?:\s+(?:about|on|for|that|:)\s+(.+))?$""").find(t)?.let { m ->
            return SonicIntent(
                mode = IntentMode.COMMAND, type = IntentType.DRAFT_NOTE, rawText = text, confidence = HIGH,
                entities = mapOf("body" to m.groupValues.getOrNull(1).orEmpty().trim()),
                requiresConfirmation = true
            )
        }

        val words = t.split(Regex("""\s+"""))
        if (words.size > 5 && !containsCommandKeywords(t)) {
            return SonicIntent(
                IntentMode.DICTATION, IntentType.FORM_FILL, rawText = text, confidence = MED,
                entities = mapOf("text" to text)
            )
        }
        return null
    }

    private fun wa(raw: String, contact: String, message: String, action: String, conf: Float = HIGH) =
        SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.WHATSAPP,
            targetApp = "com.whatsapp",
            entities = buildMap {
                put("contact", contact.replace(Regex("""\s+on\s+whats?\s?app.*$""", RegexOption.IGNORE_CASE), "").trim())
                put("action", action)
                if (message.isNotBlank()) put("message", message.trim())
            },
            rawText = raw,
            confidence = conf,
            requiresConfirmation = action == "send" && message.isNotBlank()
        )

    private fun yt(raw: String, query: String) = SonicIntent(
        mode = IntentMode.COMMAND,
        type = IntentType.YOUTUBE_SEARCH,
        targetApp = "com.google.android.youtube",
        entities = mapOf("query" to query.trim(), "action" to "play"),
        rawText = raw,
        confidence = HIGH
    )

    private fun title(s: String) = s.trim().removeSuffix("please")
        .split(" ").filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun containsCommandKeywords(text: String): Boolean {
        val keywords = listOf(
            "call", "phone", "whatsapp", "sms", "uber", "youtube", "email", "gmail",
            "open", "search", "play", "install", "wifi", "bluetooth", "alarm", "message"
        )
        return keywords.any { text.contains(it) }
    }

    // ── Open-language input (the input-mechanism upgrade) ──
    // Optional LLM fallback: wired by SonicEngine when a BYOK Gemini key exists.
    // parse() stays pure/sync for the corpus tests; parseSmart is the engine path.
    @Volatile
    var llmFallback: LlmIntentFallback? = null

    /**
     * Engine path: regex first (fast, free, private). On UNKNOWN / unknown-ish
     * low-confidence results and when the fallback is configured, classify via
     * the LLM; on any null → return the original regex result (the designed
     * clarification loop takes over unchanged). Never throws.
     */
    suspend fun parseSmart(cleanedText: String): SonicIntent {
        val regexResult = parse(cleanedText)
        val needsHelp = regexResult.type == IntentType.UNKNOWN ||
            (regexResult.confidence < MED)
        if (!needsHelp) return regexResult
        val llm = llmFallback ?: return regexResult
        val llmResult = try { llm.classify(cleanedText) } catch (e: Exception) { null }
        if (llmResult == null) return regexResult
        // Guard: the LLM result must not be WORSE than what regex found.
        return if (llmResult.confidence > regexResult.confidence) llmResult else regexResult
    }

}
