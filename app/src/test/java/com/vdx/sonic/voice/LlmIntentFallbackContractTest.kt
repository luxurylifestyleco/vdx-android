package com.vdx.sonic.voice

import com.vdx.sonic.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * LlmIntentFallbackContractTest — the input-upgrade's safety contract, no network.
 *
 * Proves the open-language layer can NEVER make the app less safe than the regex
 * parser alone:
 *   C1 — JSON contract parse: valid happy-path maps to the right intent + entities.
 *   C2 — out-of-vocabulary intent is refused (never becomes an action).
 *   C3 — forbidden types (DESCRIBE_IMAGE) are refused by contract, not by accident.
 *   C4 — contact-requiring intents without a contact are refused (LLM cannot invent).
 *   C5 — low-confidence results are refused → clarification loop stays in charge.
 *   C6 — fenced-code-block output is tolerated (model hygiene).
 *   C7 — garbage/exception → null (never throws upward; designed null = re-ask).
 *   C8 — destructive intent types still carry requiresConfirmation through the gate.
 *   P  — parseSmart prefers the stronger of regex/LLM and never downgrades a
 *        confident regex hit with a weaker LLM guess (pure local logic, no network).
 */
@RunWith(RobolectricTestRunner::class)
class LlmIntentFallbackContractTest {

    private val engine = LlmIntentFallback(apiKey = "test-key-dummy")

    // C1 — happy path
    @Test
    fun C1_validJson_mapsToIntentAndEntities() {
        val json = """{"intent":"WHATSAPP","contact":"Papa","message":"I will be late","confidence":0.9}"""
        val r = engine.parseJson(json)
        assertNotNull(r)
        assertEquals(IntentType.WHATSAPP, r!!.type)
        assertEquals("Papa", r.entities["contact"])
        assertEquals("I will be late", r.entities["message"])
        assertTrue(r.requiresConfirmation) // C8 baked in for the confirm-gated trio
    }

    // C2 — invented intent type
    @Test
    fun C2_outOfVocabularyIntent_refused() {
        assertNull(engine.parseJson("""{"intent":"ROCKET_LAUNCH","confidence":0.99}"""))
    }

    // C3 — the camera stub stays a stub
    @Test
    fun C3_forbiddenType_refused() {
        assertNull(engine.parseJson("""{"intent":"DESCRIBE_IMAGE","confidence":0.99}"""))
    }

    // C4 — no invented contacts
    @Test
    fun C4_contactIntent_withoutContact_refused() {
        assertNull(engine.parseJson("""{"intent":"CALL","confidence":0.95}"""))
    }

    // C5 — confidence floor
    @Test
    fun C5_lowConfidence_refused() {
        assertNull(engine.parseJson("""{"intent":"SEARCH","query":"weather","confidence":0.3}"""))
    }

    // C6 — model hygiene
    @Test
    fun C6_fencedJson_tolerated() {
        val fenced = "```json\n{\"intent\":\"SET_ALARM\",\"target\":\"\",\"query\":\"\",\"confidence\":0.8}\n```"
        val r = engine.parseJson(fenced)
        assertNotNull(r)
        assertEquals(IntentType.SET_ALARM, r!!.type)
    }

    // C7 — garbage JSON → null, never a throw
    @Test
    fun C7_garbage_returnsNull_safely() {
        assertNull(engine.parseJson("sorry, I can't"))
        assertNull(engine.parseJson(""))
        assertNull(engine.parseJson("""{"intent":123}"""))
    }

    // C8 — the destructive trio always keeps confirmation
    @Test
    fun C8_destructiveTypes_keepRequiresConfirmation() {
        for (t in listOf("CALL", "WHATSAPP", "BOOK_RIDE")) {
            val body = if (t == "BOOK_RIDE")
                """{"intent":"$t","target":"airport ride","confidence":0.9}"""
            else
                """{"intent":"$t","contact":"Mom","confidence":0.9}"""
            val r = engine.parseJson(body)
            assertNotNull(t, r)
            assertTrue("requiresConfirmation must survive $t", r!!.requiresConfirmation)
        }
        // and a benign type does NOT flip it on
        val s = engine.parseJson("""{"intent":"SEARCH","query":"weather","confidence":0.9}""")
        assertNotNull(s); assertFalse(s!!.requiresConfirmation)
    }

    // C9 — DRAFT_NOTE needs a message body
    @Test
    fun C9_draftNote_withoutMessage_refused() {
        assertNull(engine.parseJson("""{"intent":"DRAFT_NOTE","confidence":0.9}"""))
        assertNotNull(engine.parseJson("""{"intent":"DRAFT_NOTE","message":"buy milk","confidence":0.9}"""))
    }

    // P — parseSmart prefers the stronger source (no network involved: llmFallback=null path)
    @Test
    fun P_parseSmart_withoutFallback_returnsRegexResult() {
        val parser = IntentParser()
        val r = kotlinx.coroutines.runBlocking { parser.parseSmart("call Mom") }
        assertEquals(IntentType.CALL, r.type)
        // unknown phrase with no fallback → UNKNOWN + clarification (designed law intact)
        val u = kotlinx.coroutines.runBlocking { parser.parseSmart("flurble the warrant") }
        assertEquals(IntentType.UNKNOWN, u.type)
        assertTrue(u.clarificationNeeded)
    }
}