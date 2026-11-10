package com.vdx.sonic.voice

import com.vdx.sonic.IntentMode
import com.vdx.sonic.IntentType
import com.vdx.sonic.SonicIntent
import com.google.genai.Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * LlmIntentFallback — open-language input for VDX Sonic (the input-mechanism upgrade).
 *
 * The 49-rule IntentParser covers the designed command families. When it returns
 * UNKNOWN / a below-threshold confidence, this engine asks the configured LLM
 * (Gemini BYOK via the already-integrated GenAI SDK) to classify the SAME utterance
 * into the app's intent vocabulary. Output contract: a strict JSON object with
 * intent (one of the allowlisted types), contact, message, app, query, and
 * confidence. Anything malformed, out-of-vocabulary, or low-confidence returns
 * null — the caller then falls through to the designed clarification loop
 * ("never guess" is preserved verbatim).
 *
 * Budget: 1 request per failed parse, 8s timeout, no retries inside the engine.
 * Privacy: the utterance text goes to the model provider ONLY when the user has
 * configured their own key (BYOK) — keyless installs never call this code.
 */
class LlmIntentFallback(
    private val apiKey: String,
    private val model: String = "gemini-3.6-flash"
) {
    companion object {
        /** Allowlisted outputs — the LLM cannot invent an intent outside this set. */
        val ALLOWED = setOf(
            "CALL", "WHATSAPP", "SMS", "EMAIL", "CONTACT_MANAGE", "BOOK_RIDE",
            "APP_LAUNCH", "APP_SWITCH", "GO_BACK", "GO_HOME", "SEARCH",
            "YOUTUBE_SEARCH", "YOUTUBE_CONTROL", "PLAY_STORE", "READ_SCREEN",
            "READ_NOTIFICATIONS", "SET_ALARM", "SETTINGS_NAVIGATION",
            "SYSTEM_QUERY", "SYSTEM_TOGGLE", "DRAFT_NOTE", "CANCEL"
        )

        private const val TIMEOUT_MS = 8_000

        /** Intent types the executor is NOT allowed to act on from free speech. */
        private val FORBIDDEN = setOf("DESCRIBE_IMAGE") // camera path stays a designed stub

        private const val SYSTEM_PROMPT = """You map a spoken phone-assistant command to one intent.
Answer with STRICT JSON only, no markdown, no explanation. Schema:
{"intent":"<TYPE>","contact":"<person name or empty>","message":"<message text or empty>","target":"<app/settings target or empty>","query":"<search text or empty>","confidence":<0.0-1.0>}
Valid <TYPE> values only: CALL, WHATSAPP, SMS, EMAIL, CONTACT_MANAGE, BOOK_RIDE, APP_LAUNCH, APP_SWITCH, GO_BACK, GO_HOME, SEARCH, YOUTUBE_SEARCH, YOUTUBE_CONTROL, PLAY_STORE, READ_SCREEN, READ_NOTIFICATIONS, SET_ALARM, SETTINGS_NAVIGATION, SYSTEM_QUERY, SYSTEM_TOGGLE, DRAFT_NOTE, CANCEL.
Rules: preserve the user's language in contact/message/query values; never invent a contact not named in the utterance; money/payment/booking actions are BOOK_RIDE only for real ride words (uber, ola, cab, ride); if the utterance is not a clear command, use intent CANCEL with confidence 0.0."""
    }

    /** True when this engine is usable (non-blank key). */
    fun isReady(): Boolean = apiKey.isNotBlank()

    /**
     * Classify one utterance. Returns a [SonicIntent] on success, or null when:
     * unready / timeout / malformed / non-JSON / intent outside the allowlist /
     * forbidden type / confidence below [minConfidence]. The caller's designed
     * clarification loop handles every null — nothing here ever throws upward.
     */
    suspend fun classify(
        utterance: String,
        minConfidence: Float = 0.55f
    ): SonicIntent? = withContext(Dispatchers.IO) {
        try {
            if (!isReady() || utterance.isBlank()) return@withContext null
            val client = Client.builder().apiKey(apiKey).build()
            val prompt = "$SYSTEM_PROMPT\n\nUtterance: $utterance"
            val response = client.models.generateContent(
                model,
                prompt,
                null
            )
            val raw = response.text()?.trim() ?: return@withContext null
            parseJson(raw)
                ?.takeIf { it.confidence >= minConfidence }
        } catch (e: Exception) {
            null // honest null — clarification loop takes over
        }
    }

    /** Parse the model's JSON into a SonicIntent; null on any contract violation
     *  (including confidence below [minConfidence] — the clarification loop stays in charge). */
    fun parseJson(raw: String, minConfidence: Float = 0.55f): SonicIntent? = try {
        // tolerate a fenced code block
        val cleaned = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val obj = JSONObject(cleaned)
        val typeRaw = obj.optString("intent", "").uppercase().trim()
        if (typeRaw !in ALLOWED || typeRaw in FORBIDDEN) return null
        val conf = obj.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
        if (conf < minConfidence) return null
        val entities = buildMap {
            obj.optString("contact", "").takeIf { it.isNotBlank() }?.let { put("contact", it) }
            obj.optString("message", "").takeIf { it.isNotBlank() }?.let { put("message", it) }
            obj.optString("target", "").takeIf { it.isNotBlank() }?.let { put("target", it) }
            obj.optString("query", "").takeIf { it.isNotBlank() }?.let { put("query", it) }
        }
        // Entities must be non-empty for types that REQUIRE them (the LLM may not invent).
        when (typeRaw) {
            "CALL", "SMS", "EMAIL", "CONTACT_MANAGE", "WHATSAPP" ->
                if (!entities.containsKey("contact")) return null
            "DRAFT_NOTE" -> if (!entities.containsKey("message")) return null
        }
        val type = IntentType.valueOf(typeRaw)
        SonicIntent(
            mode = IntentMode.COMMAND,
            type = type,
            rawText = cleaned.take(200),
            confidence = conf,
            entities = entities,
            requiresConfirmation = type in setOf(
                IntentType.CALL, IntentType.WHATSAPP, IntentType.BOOK_RIDE
            )
        )
    } catch (e: Exception) {
        null
    }
}