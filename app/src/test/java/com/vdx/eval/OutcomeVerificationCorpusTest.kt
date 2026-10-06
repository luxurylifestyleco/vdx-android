package com.vdx.eval

import com.vdx.sonic.ActionPrimitive
import com.vdx.sonic.ExecutionPlan
import com.vdx.sonic.ExecutionResult
import com.vdx.sonic.IntentMode
import com.vdx.sonic.IntentType
import com.vdx.sonic.SonicIntent
import com.vdx.sonic.flows.FlowCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OutcomeVerificationCorpus — negation of the Global Testing Report FAIL rows
 * "Accessibility action / click reliability" + "Post-action verification".
 *
 * The report demands: "Move from command sent to outcome verification."
 * This corpus proves that demand holds on the REAL production plan shape:
 *
 *   P1 — plan starts with OpenApp whose package is allowlist-clean, and the app
 *        open is followed by a foreground verification wait (WaitForPackage).
 *   P2 — every SetText step is followed by a postcondition check (WaitForPackage,
 *        WaitForUserConfirmation) or a state-read — "typed" is never left assumed.
 *   P3 — every click/set-text selector is anchored (resourceId) so verification
 *        re-finds the same control; the send click never fires without an
 *        explicit user-confirmation step in front of it (consent gate).
 *   P4 — truthfulness law on the result model: ONLY verified Success passes
 *        isHonestSuccess(); Unverified/Failed/Blocked/Cancelled never do.
 *   P5 — the send flow structure: SetText -> WaitForUserConfirmation -> ClickNode;
 *        the confirmation step (the report's "ambiguity resolved before execution")
 *        sits between typing and sending — never skipped.
 *
 * Bar: 100%. A miss is a lie. Do not lower the bar to make CI green.
 */
@RunWith(RobolectricTestRunner::class)
class OutcomeVerificationCorpusTest {

    private fun whatsappPlan(action: String = "send"): ExecutionPlan {
        val intent = SonicIntent(
            mode = IntentMode.COMMAND,
            type = IntentType.WHATSAPP,
            rawText = "whatsapp",
            confidence = 0.95f,
            entities = mapOf("contact" to "Papa", "message" to "hello", "action" to action)
        )
        return FlowCatalog.plan(intent)
    }

    /** P1 — plan starts OpenApp -> WaitForPackage (foreground verification). */
    @Test
    fun P1_planOpens_withVerifiedForegroundWait() {
        val steps = whatsappPlan().steps
        assertTrue("first step must be OpenApp", steps.first().action is ActionPrimitive.OpenApp)
        val second = steps[1].action
        assertTrue(
            "second step must be WaitForPackage (foreground verification), was ${second::class.simpleName}",
            second is ActionPrimitive.WaitForPackage
        )
        val pkg = (steps.first().action as ActionPrimitive.OpenApp).packageName
        assertTrue("package allowlist-clean", pkg.all { it.isLetterOrDigit() || it == '.' })
    }

    /** P2 — every SetText is followed by a postcondition check, never assumed. */
    @Test
    fun P2_everySetText_followedByPostconditionCheck() {
        val steps = whatsappPlan("send").steps
        val types = steps.withIndex().filter { it.value.action is ActionPrimitive.SetText }
        assertTrue("plan has SetText steps", types.isNotEmpty())
        types.forEach { (i, _) ->
            val next = steps[i + 1].action
            val ok = next is ActionPrimitive.WaitForPackage ||
                next is ActionPrimitive.WaitForUserConfirmation ||
                next is ActionPrimitive.ReadUiState ||
                next is ActionPrimitive.ReadVisibleResult ||
                next is ActionPrimitive.WaitForNode
            assertTrue("step after SetText #$i must verify outcome, was ${next::class.simpleName}", ok)
        }
    }

    /** P3 — all click/text selectors anchored; consent guards the send click. */
    @Test
    fun P3_anchors_present_and_sendClick_gatedByConfirmation() {
        val steps = whatsappPlan("send").steps
        steps.forEach { step ->
            when (val a = step.action) {
                is ActionPrimitive.ClickNode -> assertNotNull(
                    "click selector anchored: ${step.description}", a.selector.resourceId)
                is ActionPrimitive.SetText -> assertNotNull(
                    "set-text selector anchored: ${step.description}", a.selector.resourceId)
                else -> {}
            }
        }
        val sendIdx = steps.indexOfFirst {
            (it.action as? ActionPrimitive.ClickNode)?.selector?.resourceId == "com.whatsapp:id/send"
        }
        assertTrue("send click exists", sendIdx > 0)
        val before = steps[sendIdx - 1].action
        assertTrue(
            "the step before the send click must be WaitForUserConfirmation (consent gate)",
            before is ActionPrimitive.WaitForUserConfirmation
        )
    }

    /** P4 — the truthfulness law, on the result model. */
    @Test
    fun P4_onlyVerifiedSuccess_isHonestSuccess() {
        assertTrue(ExecutionResult.Success("done", 1).isHonestSuccess())
        assertTrue(!ExecutionResult.Unverified("tried", "cannot confirm", "s1").isHonestSuccess())
        assertTrue(!ExecutionResult.Failed("no", recoverable = true).isHonestSuccess())
        assertTrue(!ExecutionResult.Blocked("downstream").isHonestSuccess())
        assertTrue(!ExecutionResult.Cancelled().isHonestSuccess())
    }

    /** P5 — consent-first send STRUCTURE: SetText -> Confirm -> Click, verbatim order. */
    @Test
    fun P5_sendFlow_shape_typeConfirmClick() {
        val steps = whatsappPlan("send").steps
        val tail = steps.takeLast(3).map { it.action::class.simpleName }
        assertEquals(
            "send flow must end Type->Confirm->Click",
            listOf("SetText", "WaitForUserConfirmation", "ClickNode"),
            tail
        )
        val set = steps[steps.size - 3].action as ActionPrimitive.SetText
        assertEquals("com.whatsapp:id/entry", set.selector.resourceId)
        val click = steps.last().action as ActionPrimitive.ClickNode
        assertEquals("com.whatsapp:id/send", click.selector.resourceId)
    }

    /** P1b — the open flow (no message) still ends in a readable, honest state. */
    @Test
    fun P1b_openFlow_endsInChatOpen_orAsk() {
        val steps = whatsappPlan("open").steps
        val last = steps.last().action
        val ok = last is ActionPrimitive.WaitForPackage ||
            last is ActionPrimitive.ClickNode ||
            last is ActionPrimitive.AskUser ||
            last is ActionPrimitive.WaitForUserConfirmation ||
            last is ActionPrimitive.ReadUiState
        assertTrue("open flow must end honestly, was ${last::class.simpleName}", ok)
    }
}
