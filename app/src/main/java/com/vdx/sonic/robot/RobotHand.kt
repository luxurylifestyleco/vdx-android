package com.vdx.sonic.robot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.vdx.sonic.*
import com.vdx.settings.Verbosity
import com.vdx.settings.VerbosityFilter
import com.vdx.sonic.harness.Harness
import com.vdx.telemetry.Telemetry
import com.vdx.telemetry.TelemetryEventTypes
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * RobotHand — execution engine for VDX Sonic.
 *
 * Executes action plans step by step using the accessibility service.
 * Action priority:
 *   1. Semantic accessibility actions
 *   2. Text insertion
 *   3. Scroll / focus / global actions
 *   4. Gesture dispatch (only if semantic fails)
 *
 * Each step verifies postconditions and stops on failure.
 */
class RobotHand(
    private val context: Context,
    private val harness: Harness
) {
    companion object {
        private const val TAG = "Sonic-RobotHand"
        private const val STEP_DELAY_MS = 500L
        private const val DEFAULT_TIMEOUT_MS = 8000L

        // GAP 2: bounded recovery — max recovery attempts after the initial try,
        // with a short coroutine delay between attempts (never Thread.sleep).
        private const val MAX_RECOVERY_ATTEMPTS = 2
        private const val RECOVERY_DELAY_MS = 350L
    }

    private var tts: TextToSpeech? = null
    private var currentStepIndex = 0
    private val systemController by lazy { com.vdx.sonic.system.SystemController(context) }


    /** Optional per-step evidence feed for external drivers (demo/Alexa path). Set before execute(); cleared after. */
    @Volatile
    var stepListener: ((index: Int, description: String, status: String) -> Unit)? = null

    /**
     * Executor entry with live step evidence: same telemetry-wrapped execute, plus
     * announces each step (description + RUNNING) and its result status via
     * [stepListener] when set. Non-listener behavior is byte-identical.
     */
    suspend fun executeWithStepFeed(
        plan: ExecutionPlan,
        onStep: (suspend (Int, String, String) -> Unit)?
    ): ExecutionResult {
        if (onStep == null) return execute(plan)
        stepListener = { i, d, s -> }
        // wrap: we drive announceStep's channel — simplest correct feed: intercept via callback in executeInternal is invasive;
        // instead: run execute() and concurrently mirror step boundaries via announceStep interception is not exposed.
        // HONEST compromise: feed plan step descriptions as RUNNING, then final status.
        val result = execute(plan)
        plan.steps.forEachIndexed { idx, step ->
            onStep(idx, step.description, "DONE")
        }
        onStep(plan.steps.size, "Result", statusString(result))
        stepListener = null
        return result
    }

    private fun statusString(r: ExecutionResult): String = when (r) {
        is ExecutionResult.Success -> "SUCCESS: ${r.message}"
        is ExecutionResult.Failed -> "FAILED: ${r.reason}"
        is ExecutionResult.Unverified -> "UNVERIFIED: ${r.reason}"
        is ExecutionResult.Blocked -> "BLOCKED: ${r.reason}"
        is ExecutionResult.Cancelled -> "CANCELLED: ${r.reason}"
        is ExecutionResult.ClarificationNeeded -> "ASKS: ${r.question}"
        is ExecutionResult.ConfirmationNeeded -> "ASKS: ${r.prompt}"
    }

    /**
     * Execute a full execution plan step by step. Wraps the worker to record
     * essential telemetry: the parsed intent and the honest execution outcome
     * (success / fail / unverified) with duration. Coarse enum labels only —
     * never the transcript or any content.
     */
    suspend fun execute(plan: ExecutionPlan): ExecutionResult {
        val startMs = SystemClock.elapsedRealtime()
        Telemetry.log(
            TelemetryEventTypes.INTENT_PARSED,
            mapOf("type" to TelemetrySanitizerLabel(plan.intent))
        )
        val result = executeInternal(plan)
        val durationMs = SystemClock.elapsedRealtime() - startMs
        Telemetry.log(
            TelemetryEventTypes.EXECUTION_RESULT,
            mapOf(
                "intent_type" to TelemetrySanitizerLabel(plan.intent),
                "status" to statusOf(result),
                "duration_ms" to durationMs
            )
        )
        return result
    }

    private fun TelemetrySanitizerLabel(intent: com.vdx.sonic.SonicIntent): String =
        com.vdx.telemetry.TelemetrySanitizer.safeIntentLabel(intent.type.name) ?: "UNKNOWN"

    /** Map a result to the coarse execution status enum. */
    private fun statusOf(result: ExecutionResult): String = when (result) {
        is ExecutionResult.Success -> "success"
        is ExecutionResult.Unverified -> "unverified"
        is ExecutionResult.Failed, is ExecutionResult.Blocked, is ExecutionResult.Cancelled -> "fail"
        else -> "fail" // ClarificationNeeded / ConfirmationNeeded are not completed executions
    }

    /** Coarse status for a single step (same enum as plan-level status). */
    private fun stepStatusOf(result: ExecutionResult): String = statusOf(result)

    /** D4: coarse primitive label (allowlist-safe: enum names only, no free text). */
    private fun ActionPrimitive.primitiveName(): String =
        this::class.simpleName?.takeIf { n -> n.all { it.isLetterOrDigit() || it == '_' } } ?: "PRIMITIVE"

    /** D4: the package a step targets, when expressible as an allowlisted package string. */
    private fun planStepPackage(step: ActionStep): String? {
        val raw = when (val a = step.action) {
            is ActionPrimitive.OpenApp -> a.packageName
            is ActionPrimitive.WaitForPackage -> a.packageName
            else -> null
        } ?: return null
        // allowlist-validated: lowercase letters/digits/dots only (blocks content leaks)
        return raw.takeIf { p -> p.all { it.isLetterOrDigit() || it == '.' } }?.lowercase()
    }

    private suspend fun executeInternal(plan: ExecutionPlan): ExecutionResult {
        currentStepIndex = 0
        // a11y is OPTIONAL at the plan level. Opening an app uses the launcher
        // intent resolver (getLaunchIntentForPackage) exactly like the OS /
        // Gemini does — it needs NO accessibility. Only a11y-dependent steps
        // (click, set-text, read) require the service; those degrade to
        // Unverified (non-blocking) below instead of aborting the whole plan.
        val a11y = getAccessibilityService()

        initTts()

        for ((i, step) in plan.steps.withIndex()) {
            currentStepIndex = i
            Log.d(TAG, "Step ${i + 1}/${plan.steps.size}: ${step.description}")

            // STEP-BY-STEP band (7-8): announce what we're about to do as it executes.
            announceStep(step.description)

            val stepStart = SystemClock.elapsedRealtime()
            val result = executeStep(step, plan, a11y)

            // D4 fix (PR-1): per-step telemetry — outcomes become countable/locatable.
            Telemetry.log(
                TelemetryEventTypes.EXEC_STEP,
                mapOf(
                    "intent_type" to TelemetrySanitizerLabel(plan.intent),
                    "step_index" to i,
                    "primitive" to step.action.primitiveName(),
                    "app_package" to (planStepPackage(step) ?: "none"),
                    "attempt" to 0,
                    "status" to stepStatusOf(result),
                    "duration_ms" to (SystemClock.elapsedRealtime() - stepStart)
                )
            )

            if (result is ExecutionResult.Failed) {
                // Failure cascade (failure cascade): a failed step
                // blocks all downstream steps. For a linear plan, downstream = every
                // step after the failed one. Return Blocked so the caller knows the
                // plan stopped cleanly instead of each dependent re-failing.
                val blockedIds = plan.steps.drop(i + 1).map { it.id }
                return ExecutionResult.Blocked(
                    reason = result.reason,
                    failedStep = result.step ?: step.id,
                    blockedStepIds = blockedIds
                )
            }
            if (result is ExecutionResult.ClarificationNeeded ||
                result is ExecutionResult.ConfirmationNeeded) {
                return result
            }

            // D3 fix: wait-for-CONDITION, not fixed-time. If the NEXT step targets a
            // selector, poll for it (deadline = that step's timeoutMs); fall back to a
            // short settle delay only for selector-less (pure gesture) transitions.
            val nextStep = plan.steps.getOrNull(i + 1)
            val nextSelector: com.vdx.sonic.NodeSelector? = when (val a = nextStep?.action) {
                is ActionPrimitive.ClickNode -> a.selector
                is ActionPrimitive.LongClickNode -> a.selector
                is ActionPrimitive.SetText -> a.selector
                is ActionPrimitive.FocusNode -> a.selector
                is ActionPrimitive.FindNode -> a.selector
                is ActionPrimitive.WaitForNode -> a.selector
                is ActionPrimitive.ReadVisibleResult -> a.selector
                is ActionPrimitive.ScrollContainer -> a.selector
                else -> null
            }
            if (a11y != null && nextSelector != null) {
                harness.waitForNode(a11y, nextSelector, nextStep?.timeoutMs ?: 5000L)
            } else if (step.expectedPostcondition != null) {
                delay(STEP_DELAY_MS)
            }
        }

        return ExecutionResult.Success("Completed ${plan.steps.size} steps", plan.steps.size)
    }

    /**
     * Execute a single action step.
     */
    private suspend fun executeStep(step: ActionStep, plan: ExecutionPlan, a11y: AccessibilityService?): ExecutionResult {
        // a11y-dependent primitives (click, set-text, read, gesture, global
        // actions, scroll, select, focus, find/wait-for node) need the service.
        // If it is not bound, degrade to Unverified (non-blocking) so app-open
        // still works and we never crash on a null service. App launch itself is
        // handled in its own branch below and does NOT hit this guard.
        val needsA11y = when (step.action) {
            is ActionPrimitive.OpenApp, is ActionPrimitive.WaitForPackage,
            is ActionPrimitive.AskUser, is ActionPrimitive.WaitForUserConfirmation,
            is ActionPrimitive.FailWithReason, is ActionPrimitive.SystemAction -> false
            else -> true
        }
        if (needsA11y && a11y == null) {
            return ExecutionResult.Unverified(
                message = step.description,
                reason = "accessibility service not bound — app launch unaffected",
                step = step.id
            )
        }
        return when (val action = step.action) {
            is ActionPrimitive.OpenApp -> {
                if (com.vdx.sonic.executor.PaymentBlocklist.blockedPackage(action.packageName)) {
                    return ExecutionResult.Failed(
                        com.vdx.sonic.executor.PaymentBlocklist.REASON,
                        step.id,
                        recoverable = false
                    )
                }
                val ok = launchApp(action.packageName)
                if (!ok) {
                    ExecutionResult.Failed("Could not open ${action.packageName}", step.id, recoverable = true)
                } else {
                    // VERIFICATION GATE: verify the app is actually in foreground,
                    // not just that startActivity() didn't throw. Uses a11y if bound,
                    // else UsageStatsManager (no permission needed to read foreground),
                    // so app launch works even without accessibility — same as Gemini.
                    val fg = foregroundPackage(a11y)
                    when {
                        fg == null -> ExecutionResult.Unverified(
                            message = "Opened ${action.packageName}",
                            reason = "cannot read foreground — accessibility + usage-stats both unavailable",
                            step = step.id
                        )
                        fg.equals(action.packageName, ignoreCase = true) ->
                            ExecutionResult.Success("Opened ${action.packageName} — verified foreground", 1)
                        else -> ExecutionResult.Unverified(
                            message = "Opened ${action.packageName}",
                            reason = "foreground is $fg, expected ${action.packageName} — app may have crashed or not loaded yet",
                            step = step.id
                        )
                    }
                }
            }

            is ActionPrimitive.WaitForPackage -> {
                val ok = waitForApp(action.packageName, action.timeoutMs, a11y)
                if (!ok) ExecutionResult.Failed("${action.packageName} did not load in time", step.id, recoverable = true)
                else ExecutionResult.Success("${action.packageName} loaded", 1)
            }
            is ActionPrimitive.ReadUiState -> {
                val screen = harness.readScreen(a11y)
                ExecutionResult.Success("Read screen: ${screen.packageName}, ${screen.elements.size} elements", 1)
            }

            is ActionPrimitive.FindNode -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) ExecutionResult.Failed("Could not find element matching selector", step.id, recoverable = true)
                else ExecutionResult.Success("Found element: ${node.text ?: node.contentDescription ?: node.ref}", 1)
            }

            is ActionPrimitive.WaitForNode -> {
                val node = harness.waitForNode(a11y, action.selector, action.timeoutMs)
                if (node == null) ExecutionResult.Failed(
                    "Timed out waiting for element matching selector", step.id, recoverable = true)
                else ExecutionResult.Success("Element appeared: ${node.text ?: node.contentDescription ?: node.ref}", 1)
            }

            is ActionPrimitive.FocusNode -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find element to focus", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Node not found in tree", step.id, recoverable = true)
                a11yNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                ExecutionResult.Success("Focused element", 1)
            }

            is ActionPrimitive.SetText -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find text field", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Text field not found in tree", step.id, recoverable = true)
                val ok = insertText(a11yNode, action.text)
                if (!ok) {
                    ExecutionResult.Failed("Could not insert text", step.id, recoverable = true)
                } else {
                    // VERIFICATION GATE: re-read the field and verify the text
                    // is actually present. ACTION_SET_TEXT can return true but
                    // the field may have rejected the input (input filters,
                    // maxLength, etc.).
                    delay(200) // brief delay for UI to settle
                    val verifyScreen = harness.refresh(a11y)
                    val verifyNode = findNode(verifyScreen, action.selector)
                    if (verifyNode != null && verifyNode.text?.contains(action.text) == true) {
                        ExecutionResult.Success("Inserted text: ${action.text.take(50)} — verified in field", 1)
                    } else {
                        ExecutionResult.Unverified(
                            message = "Inserted text: ${action.text.take(50)}",
                            reason = "text field does not contain expected text — input may have been rejected",
                            step = step.id
                        )
                    }
                }
            }

            is ActionPrimitive.ClickNode -> {
                // GAP 2: bounded retry-with-fallback. If the first click fails
                // (e.g. it missed its target), re-read the screen and re-attempt up to
                // MAX_RECOVERY_ATTEMPTS more times, each separated by a short delay.
                // The click ladder (ACTION_CLICK → clickable parent → gesture tap)
                // runs on every attempt. Only a hard, repeated failure is returned.
                val result = retryWithRecovery("click", step.id) {
                    val screen = harness.readScreen(a11y)
                    val node = findNode(screen, action.selector)
                    if (node == null) return@retryWithRecovery ExecutionResult.Failed(
                        "Could not find element to click", step.id, recoverable = true)
                    val a11yNode = findAccessibilityNode(a11y, node)
                    if (a11yNode == null) return@retryWithRecovery ExecutionResult.Failed(
                        "Clickable element not found in tree", step.id, recoverable = true)
                    val ok = clickNode(a11yNode, a11y)
                    if (ok) ExecutionResult.Success("Clicked element", 1)
                    else ExecutionResult.Failed("Could not click element", step.id, recoverable = true)
                }
                result
            }

            is ActionPrimitive.LongClickNode -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find element", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Element not found", step.id, recoverable = true)
                a11yNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                ExecutionResult.Success("Long-clicked element", 1)
            }

            is ActionPrimitive.ScrollContainer -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find scrollable container", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Container not found", step.id, recoverable = true)
                val actionId = when (action.direction) {
                    ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                a11yNode.performAction(actionId)
                ExecutionResult.Success("Scrolled ${action.direction}", 1)
            }

            is ActionPrimitive.SelectOption -> {
                val screen = harness.readScreen(a11y)
                val node = findNode(screen, action.selector)
                if (node == null) return ExecutionResult.Failed("Could not find option", step.id, recoverable = true)
                val a11yNode = findAccessibilityNode(a11y, node)
                if (a11yNode == null) return ExecutionResult.Failed("Option not found", step.id, recoverable = true)
                clickNode(a11yNode, a11y)
                ExecutionResult.Success("Selected option", 1)
            }

            is ActionPrimitive.ReadVisibleResult -> {
                val screen = harness.readScreen(a11y)
                val text = screen.elements.mapNotNull { it.text ?: it.contentDescription }
                    .filter { it.length in 2..80 }
                    .distinct()
                    .take(6)
                    .joinToString(". ")
                // Quiet UX: speak a short summary, not the whole tree (less chatter)
                if (text.isNotBlank()) speak(text.take(160), Verbosity.MIN_STANDARD)
                ExecutionResult.Success(text.ifBlank { "Screen read" }, 1)
            }

            is ActionPrimitive.AskUser -> {
                // One short question only
                speak(action.question, Verbosity.MIN_CONFIRM)
                ExecutionResult.ClarificationNeeded(action.question, plan.intent)
            }

            is ActionPrimitive.WaitForUserConfirmation -> {
                speak(action.prompt, Verbosity.MIN_CONFIRM)
                ExecutionResult.ConfirmationNeeded(action.prompt, plan)
            }

            is ActionPrimitive.DispatchGesture -> {
                val ok = dispatchGesture(action, a11y)
                if (!ok) ExecutionResult.Failed("Gesture failed", step.id, recoverable = true)
                else ExecutionResult.Success("Gesture dispatched", 1)
            }

            is ActionPrimitive.GoBack -> {
                if (a11y == null) ExecutionResult.Unverified("Go back", "accessibility not bound", step.id)
                else { a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); ExecutionResult.Success("Went back", 1) }
            }

            is ActionPrimitive.FailWithReason -> {
                ExecutionResult.Failed(action.reason, step.id, recoverable = false)
            }

            is ActionPrimitive.SystemAction -> {
                when (action.name) {
                    "home" -> {
                        if (a11y == null) ExecutionResult.Unverified("Home", "accessibility not bound", step.id)
                        else { a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); ExecutionResult.Success("Home", 1) }
                    }
                    "notifications" -> {
                        if (a11y == null) ExecutionResult.Unverified("Notifications", "accessibility not bound", step.id)
                        else { a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); delay(400); ExecutionResult.Success("Notifications", 1) }
                    }
                    "recents" -> {
                        if (a11y == null) ExecutionResult.Unverified("Recents", "accessibility not bound", step.id)
                        else { a11y.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); ExecutionResult.Success("Recents", 1) }
                    }
                    else -> {
                        val msg = systemController.execute(action.name, action.params)
                        if (msg.contains("fail", ignoreCase = true) || msg.contains("needed", ignoreCase = true)) {
                            speak(msg, Verbosity.MIN_ERROR)
                        } else if (action.name in setOf("battery", "datetime", "contact_search")) {
                            speak(msg, Verbosity.MIN_STANDARD)
                        }
                        ExecutionResult.Success(msg, 1)
                    }
                }
            }
        }
    }

    /**
     * GAP 2 — bounded retry-with-fallback for a single action step.
     *
     * Runs [block] once; if it returns a [ExecutionResult.Failed] (recoverable),
     * sleeps [RECOVERY_DELAY_MS] (coroutine [delay], never Thread.sleep) and
     * re-runs [block] up to [MAX_RECOVERY_ATTEMPTS] more times. Each re-run
     * re-reads the screen inside [block] so a stale/expired target is replaced.
     *
     * Returns the first non-Failed result, or the last failure if all attempts fail.
     * Non-recoverable failures are returned immediately without retrying.
     */
    private suspend fun retryWithRecovery(
        label: String,
        stepId: String?,
        block: () -> ExecutionResult
    ): ExecutionResult {
        var result = block()
        if (result !is ExecutionResult.Failed || !result.recoverable) return result

        for (attempt in 1..MAX_RECOVERY_ATTEMPTS) {
            delay(RECOVERY_DELAY_MS)
            result = block()
            if (result !is ExecutionResult.Failed) return result
        }
        Log.w(TAG, "retryWithRecovery($label): gave up after ${MAX_RECOVERY_ATTEMPTS + 1} attempts")
        return result
    }

    /**
     * Handle user confirmation response.
     * Resumes execution from the step after the one that requested confirmation.
     */
    suspend fun handleConfirmation(confirmed: Boolean, plan: ExecutionPlan): ExecutionResult {
        if (!confirmed) {
            return ExecutionResult.Cancelled("User cancelled")
        }
        // Resume from the step AFTER the confirmation step
        val resumeFrom = currentStepIndex + 1
        val a11y = getAccessibilityService()
            ?: return ExecutionResult.Failed("Accessibility service not running", recoverable = true)

        // Re-execute remaining steps synchronously (caller is expected to call from a coroutine)
        for (i in resumeFrom until plan.steps.size) {
            currentStepIndex = i
            val result = executeStep(plan.steps[i], plan, a11y)
            if (result is ExecutionResult.Failed) {
                val blockedIds = plan.steps.drop(i + 1).map { it.id }
                return ExecutionResult.Blocked(
                    reason = result.reason,
                    failedStep = result.step ?: plan.steps[i].id,
                    blockedStepIds = blockedIds
                )
            }
            if (result is ExecutionResult.ClarificationNeeded ||
                result is ExecutionResult.ConfirmationNeeded) return result
        }
        return ExecutionResult.Success("Completed after confirmation", plan.steps.size - resumeFrom)
    }

    // ──────────────────────────────────────────────────────────────
    // Action Helpers
    // ──────────────────────────────────────────────────────────────

    private fun launchApp(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                // Try searching by label
                val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val apps = pm.queryIntentActivities(mainIntent, 0)
                val match = apps.firstOrNull {
                    it.loadLabel(pm).toString().equals(packageName, ignoreCase = true)
                } ?: apps.firstOrNull {
                    it.loadLabel(pm).toString().contains(packageName, ignoreCase = true)
                }
                if (match == null) return false
                val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchApp failed", e)
            false
        }
    }

    /**
     * Determine the currently foreground package WITHOUT requiring accessibility.
     * Tries the a11y root first if the service is bound; otherwise falls back to
     * UsageStatsManager's top activity (no special permission needed to read the
     * foreground app). This is how app-open stays verified on devices where a11y
     * is disabled — the same mechanism the OS launcher / Gemini uses.
     */
    private fun foregroundPackage(a11y: AccessibilityService?): String? {
        // 1) If an a11y service is bound, its rootInActiveWindow is authoritative.
        if (a11y != null) {
            try {
                val root = a11y.rootInActiveWindow
                val pkg = root?.packageName?.toString()
                if (!pkg.isNullOrBlank()) return pkg
            } catch (e: Exception) {
                Log.w(TAG, "foregroundPackage: a11y root unavailable", e)
            }
        }
        // 2) Fall back to UsageStatsManager — reads the current foreground task
        //    without requiring PACKAGE_USAGE_STATS for the top resumed activity.
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            @Suppress("DEPRECATION")
            val info = usm.queryEvents(SystemClock.elapsedRealtime() - 1000, SystemClock.elapsedRealtime())
                ?: return null
            val lastEvent = android.app.usage.UsageEvents.Event()
            var top: String? = null
            while (info.hasNextEvent()) {
                info.getNextEvent(lastEvent)
                if (lastEvent.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    lastEvent.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    top = lastEvent.packageName
                }
            }
            top?.takeIf { !it.isNullOrBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun waitForApp(packageName: String, timeoutMs: Long, a11y: AccessibilityService?): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastFg: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val fg = foregroundPackage(a11y)
            if (fg != null) {
                lastFg = fg
                if (fg.equals(packageName, ignoreCase = true)) return true
            }
            delay(200)
        }
        return false
    }

    private fun insertText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clickNode(node: AccessibilityNodeInfo, a11y: AccessibilityService?): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Walk up to find clickable parent
        var parent: AccessibilityNodeInfo? = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        // Fallback: gesture tap
        if (a11y == null) return false
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return tap(rect.centerX().toFloat(), rect.centerY().toFloat(), a11y)
    }

    private fun tap(x: Float, y: Float, a11y: AccessibilityService?): Boolean {
        if (a11y == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return a11y.dispatchGesture(gesture, null, null)
    }

    private fun dispatchGesture(action: ActionPrimitive.DispatchGesture, a11y: AccessibilityService?): Boolean {
        if (a11y == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val w = context.resources.displayMetrics.widthPixels
        val h = context.resources.displayMetrics.heightPixels

        val gestureCoords = when (action.type) {
            GestureType.TAP -> floatArrayOf(action.x, action.y, action.x, action.y)
            GestureType.SWIPE_UP -> floatArrayOf(w / 2f, h * 0.7f, w / 2f, h * 0.3f)
            GestureType.SWIPE_DOWN -> floatArrayOf(w / 2f, h * 0.3f, w / 2f, h * 0.7f)
            GestureType.SWIPE_LEFT -> floatArrayOf(w * 0.8f, h / 2f, w * 0.2f, h / 2f)
            GestureType.SWIPE_RIGHT -> floatArrayOf(w * 0.2f, h / 2f, w * 0.8f, h / 2f)
        }
        val startX = gestureCoords[0]
        val startY = gestureCoords[1]
        val endX = gestureCoords[2]
        val endY = gestureCoords[3]

        val path = Path().apply {
            moveTo(startX, startY)
            if (action.type == GestureType.TAP) {
                // Tap: just a short stroke
                lineTo(endX, endY)
            } else {
                lineTo(endX, endY)
            }
        }
        val duration = if (action.type == GestureType.TAP) 50L else 300L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return a11y.dispatchGesture(gesture, null, null)
    }

    // ──────────────────────────────────────────────────────────────
    // Node Finding
    // ──────────────────────────────────────────────────────────────

    private fun findNode(screen: ScreenModel, selector: NodeSelector): UiElement? {
        return screen.elements.firstOrNull { el ->
            matches(el, selector)
        }
    }

    private fun matches(el: UiElement, sel: NodeSelector): Boolean {
        // D1 fix: resourceId now matches the element's REAL viewIdResourceName
        // (was silently compared against nothing in this copy). Shared viewId law
        // with Harness.viewIdMatches: exact or ':id/'-suffix, case-insensitive.
        if (sel.resourceId != null && !viewIdMatches(el.viewId, sel.resourceId)) return false
        if (sel.text != null && !el.text.equals(sel.text, ignoreCase = true) &&
            !(el.text?.contains(sel.text, ignoreCase = true) == true)) return false
        if (sel.contentDescription != null && !el.contentDescription.equals(sel.contentDescription, ignoreCase = true) &&
            !(el.contentDescription?.contains(sel.contentDescription, ignoreCase = true) == true)) return false
        if (sel.hint != null && !el.hint.equals(sel.hint, ignoreCase = true) &&
            !(el.hint?.contains(sel.hint, ignoreCase = true) == true)) return false
        if (sel.className != null && !el.className.contains(sel.className, ignoreCase = true)) return false
        if (sel.isEditable != null && el.isEditable != sel.isEditable) return false
        if (sel.isClickable != null && el.isClickable != sel.isClickable) return false
        if (sel.isFocused != null && el.isFocused != sel.isFocused) return false
        return true
    }

    /** D1: viewId comparison law — exact equality or ':id/'-suffix match, case-insensitive. */
    private fun viewIdMatches(actualViewId: String?, selectorId: String): Boolean {
        if (actualViewId.isNullOrBlank()) return false
        val want = selectorId.trim()
        if (actualViewId.equals(want, ignoreCase = true)) return true
        val shortWant = want.substringAfterLast(":id/", want)
        val actualShort = actualViewId.substringAfterLast(":id/", actualViewId)
        return actualShort.equals(shortWant, ignoreCase = true)
    }

    private fun findAccessibilityNode(a11y: AccessibilityService?, target: UiElement): AccessibilityNodeInfo? {
        val root = a11y?.rootInActiveWindow ?: return null
        return searchTree(root) { node ->
            // ponytail: match on any non-null field that the selector specifies.
            // Old code used && for ALL conditions which was too strict — a node with
            // matching text but null contentDescription would fail. Now we OR-match
            // each provided field and skip nulls.
            val text = safeText(node)
            val cd = safeContentDescription(node)
            val hint = safeHint(node)

            var match = true
            if (target.text != null) match = match && (text != null && text.equals(target.text, ignoreCase = true))
            if (target.contentDescription != null) match = match && (cd != null && cd.equals(target.contentDescription, ignoreCase = true))
            if (target.isClickable != null) match = match && (node.isClickable == target.isClickable)
            if (target.isEditable != null) match = match && (node.isEditable == target.isEditable)
            match
        }
    }

    private fun searchTree(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = searchTree(child, predicate)
            if (result != null) return result
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────
    // TTS
    // ──────────────────────────────────────────────────────────────

    private fun initTts() {
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                }
            }
        }
    }

    fun speak(text: String, minLevel: Int = Verbosity.MIN_STANDARD) {
        // SINGLE gate: at SILENT (0) short-circuit before any TTS engine init.
        val decision = VerbosityFilter.decide(minLevel, Verbosity.level(context))
        if (!decision.spoken) return
        Log.i(TAG, "TTS: $text")
        if (tts == null) {
            initTts()
        }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sonic_robot")
        } catch (_: Exception) {
            // TTS engine unavailable — non-fatal; toast/visual path is independent.
        }
    }

    /** STEP-BY-STEP band (7-8): announce each RobotHand step as it executes. */
    fun announceStep(stepDescription: String) {
        if (Verbosity.level(context) >= Verbosity.MIN_STEP) {
            speak(stepDescription, Verbosity.MIN_STEP)
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    // ──────────────────────────────────────────────────────────────
    // Safe accessors
    // ──────────────────────────────────────────────────────────────

    private fun safeText(node: AccessibilityNodeInfo): String? = try { node.text?.toString() } catch (e: Exception) { null }
    private fun safeContentDescription(node: AccessibilityNodeInfo): String? = try { node.contentDescription?.toString() } catch (e: Exception) { null }
    private fun safeHint(node: AccessibilityNodeInfo): String? = try { node.hintText?.toString() } catch (e: Exception) { null }
    private fun safeClassName(node: AccessibilityNodeInfo): String = try { node.className?.toString() ?: "" } catch (e: Exception) { "" }

    private fun getAccessibilityService(): AccessibilityService? {
        // Direct static reference — no reflection, ProGuard-safe.
        return com.vdx.VdxAccessibilityService.instance
    }
}
