package com.vdx.demo

import android.content.Context
import android.content.Intent
import android.util.Log
import com.vdx.ExternalAutomationReceiver
import com.vdx.sonic.ExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * DemoDriver — the "Alexa" side of the boss's one-complete-journey demo, in-app.
 *
 * The boss's journey: (1) request enters through ONE adapter → (2) core selects
 * ONE registered capability → (3) user approves where required → (4) executed on
 * the physical phone → (5) evidence returned → (6) the tree displays the correct
 * outcome (offline / cancelled / unverified included).
 *
 * In this demo build, [sendCommand] plays the role of Alexa's relay: it submits a
 * spoken-style phrase through the EXACT production path (SonicEngine → parse →
 * safety gates → plan → RobotHand) and collects the structured result — the same
 * JSON shape the Lambda relay would hand back to a real Alexa skill later. No
 * shortcuts are taken around the safety gates; nothing bypassed.
 *
 * Evidence model (truth-tree flavors — never fake "done"):
 *   SUCCESS      verified outcome observed
 *   UNVERIFIED   attempted; the phone cannot confirm the final state
 *   BLOCKED      a step failed; downstream steps never ran
 *   CANCELLED    the user said no (confirmation declined)
 *   CLARIFY      the app asks before it guesses (ambiguous request)
 */
object DemoDriver {

    private const val TAG = "DemoDriver"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Live results for the demo evidence screen, keyed by taskId. */
    val results = LinkedHashMap<String, TaskRecord>(16, 0.75f, true)
    private val listeners = mutableListOf<(TaskRecord) -> Unit>()

    data class StepStatus(val stepIndex: Int, val description: String, val status: String)

    data class TaskRecord(
        val id: String,
        val phrase: String,
        var driver: String = "Alexa (demo)",
        var flavor: String = "RUNNING",           // RUNNING | SUCCESS | UNVERIFIED | BLOCKED | CANCELLED | CLARIFY
        var headline: String = "Working…",
        val steps: MutableList<StepStatus> = mutableListOf(),
        val startedAt: Long = System.currentTimeMillis(),
        var finishedAt: Long = 0
    )

    fun addListener(l: (TaskRecord) -> Unit) { listeners.add(l) }

    /**
     * Receive an "Alexa" phrase and run it through the production pipeline.
     * Returns the taskId (the proof handle). Evidence appears in [results].
     */
    fun sendCommand(context: Context, phrase: String, driver: String = "Alexa (demo)"): String {
        val id = UUID.randomUUID().toString().take(8)
        val rec = TaskRecord(id = id, phrase = phrase, driver = driver)
        synchronized(results) { results[id] = rec }
        emit(rec)

        scope.launch {
            // THE SAME DOOR the assistant's typed path uses — no demo shortcut:
            val engine = com.vdx.sonic.SonicEngine(context)
            var result: ExecutionResult? = null
            try {
                // submitTask fires the production pipeline but returns Unit; for the
                // demo we need the result object — call the pipeline pieces directly
                // with the SAME gates ordered exactly as processText does.
                result = engine.submitTaskForDemo(phrase) { stepIdx, desc, status ->
                    rec.steps.add(StepStatus(stepIdx, desc, status))
                    emit(rec)
                }
            } catch (e: Exception) {
                Log.e(TAG, "demo task failed", e)
            }
            rec.finishedAt = System.currentTimeMillis()
            when (result) {
                is ExecutionResult.Success -> {
                    rec.flavor = "SUCCESS"
                    rec.headline = (result as ExecutionResult.Success).message
                }
                is ExecutionResult.Unverified -> {
                    rec.flavor = "UNVERIFIED"
                    rec.headline = "Tried, but can't confirm: ${(result as ExecutionResult.Unverified).reason}"
                }
                is ExecutionResult.Blocked -> {
                    rec.flavor = "BLOCKED"
                    rec.headline = "Blocked: ${(result as ExecutionResult.Blocked).reason}"
                }
                is ExecutionResult.Cancelled -> {
                    rec.flavor = "CANCELLED"
                    rec.headline = (result as ExecutionResult.Cancelled).reason
                }
                is ExecutionResult.ClarificationNeeded -> {
                    rec.flavor = "CLARIFY"
                    rec.headline = "Asks: ${(result as ExecutionResult.ClarificationNeeded).question}"
                }
                is ExecutionResult.ConfirmationNeeded -> {
                    rec.flavor = "CLARIFY"
                    rec.headline = "Asks: ${(result as ExecutionResult.ConfirmationNeeded).prompt}"
                }
                else -> {
                    rec.flavor = "BLOCKED"
                    rec.headline = "No result — check permissions/state"
                }
            }
            // Telemetry: the same channel, honest shape
            com.vdx.telemetry.Telemetry.log(
                com.vdx.telemetry.TelemetryEventTypes.EXECUTION_RESULT,
                mapOf("driver" to "demo", "status" to rec.flavor)
            )
            emit(rec)
        }
        return id
    }

    /** Export ONE record as the evidence JSON the relay would return to Alexa. */
    fun evidenceJson(rec: TaskRecord): String {
        val arr = JSONArray()
        rec.steps.forEach { s ->
            arr.put(JSONObject().put("step", s.stepIndex).put("description", s.description).put("status", s.status))
        }
        return JSONObject()
            .put("taskId", rec.id)
            .put("driver", rec.driver)
            .put("phrase", rec.phrase)
            .put("outcome", rec.flavor)
            .put("headline", rec.headline)
            .put("durationMs", if (rec.finishedAt > 0) rec.finishedAt - rec.startedAt else 0)
            .put("steps", arr)
            .toString(2)
    }

    private fun emit(rec: TaskRecord) {
        listeners.forEach { l ->
            try { l(rec) } catch (_: Exception) { }
        }
    }
}