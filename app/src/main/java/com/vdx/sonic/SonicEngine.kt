package com.vdx.sonic

import android.content.Context
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.util.Log
import com.vdx.memory.MemoryStore
import com.vdx.memory.MemoryToActionPipeline
import com.vdx.settings.KeyVault
import com.vdx.settings.Verbosity
import com.vdx.settings.VerbosityFilter
import com.vdx.sonic.intentir.IntentIrV1
import com.vdx.sonic.clarify.ClarificationEngine
import com.vdx.sonic.diag.DiagnosticsEngine
import com.vdx.sonic.harness.Harness
import com.vdx.sonic.plan.Planner
import com.vdx.sonic.robot.RobotHand as SonicRobotHand
import com.vdx.sonic.voice.CleanupEngine
import com.vdx.sonic.voice.EntityRepairEngine
import com.vdx.sonic.voice.GeminiTtsEngine
import com.vdx.sonic.voice.GroqAsrEngine
import com.vdx.sonic.voice.IntentParser
import com.vdx.sonic.voice.LocalCleanupEngine
import com.vdx.sonic.voice.PromptTemplate
import com.vdx.sonic.voice.SarvamAsrEngine
import com.vdx.telemetry.Telemetry
import com.vdx.telemetry.TelemetryEventTypes
import kotlinx.coroutines.*

/**
 * SonicEngine — live orchestrator for the 10-layer VDX Sonic pipeline.
 *
 * Bubble → MicCapture → ASR → Cleanup → EntityRepair → IntentParse
 * → Clarify? → Plan (adapters) → Sonic RobotHand → Result
 */
class SonicEngine(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "SonicEngine"
        private const val PREFS = "vdx_prefs"
    }

    val diagnostics: DiagnosticsEngine by lazy { DiagnosticsEngine(context) }
    val harness: Harness by lazy { Harness() }
    val entityRepair: EntityRepairEngine by lazy { EntityRepairEngine(context) }
    val intentParser: IntentParser by lazy { IntentParser() }
    val planner: Planner by lazy { Planner(context) }
    val clarification: ClarificationEngine by lazy { ClarificationEngine() }
    private val sonicRobot: SonicRobotHand by lazy { SonicRobotHand(context, harness) }

    /** RobotHand for the voice path. Optional [com.vdx.sonic.mcp.McpToolRegistry] can wrap this later. */
    val robotHand: SonicRobotHand get() = sonicRobot
    private val memoryStore: MemoryStore by lazy { MemoryStore(context) }

    /** Memory-to-Action vertical slice — the ONLY executor for DRAFT_NOTE. */
    val memoryToAction: MemoryToActionPipeline by lazy { MemoryToActionPipeline(context) }

    /** Per-service-instance session binding for confirmation tokens. */
    private val sessionId: String = java.util.UUID.randomUUID().toString()

    /** Pending DRAFT_NOTE proposal awaiting the user's spoken "yes". */
    private var pendingProposal: MemoryToActionPipeline.Proposal? = null

    private var tts: TextToSpeech? = null
    private var currentJob: Job? = null

    var onStateChange: ((BubbleState) -> Unit)? = null
    var onPartialTranscript: ((String) -> Unit)? = null
    var onClarification: ((ClarificationRequest) -> Unit)? = null
    var onResult: ((ExecutionResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var groqAsr: GroqAsrEngine? = null
    private var sarvamAsr: SarvamAsrEngine? = null
    private var geminiTts: GeminiTtsEngine? = null
    private var cleanupEngine: CleanupEngine? = null

    /**
     * VAD endpoint for the raw-PCM mic path. Analyzes the captured buffer and decides
     * whether it actually contains speech before handing it to ASR, so silent /
     * too-short captures are discarded instead of making Whisper hallucinate short
     * phrases on silence. Null if VAD init failed (path degrades gracefully to the
     * previous behavior of transcribing whatever was captured).
     */
    private var vadEndpoint: com.vdx.sonic.voice.vad.VadWebRtc? = null

    init {
        autoConfigureFromPrefs()
    }

    fun autoConfigureFromPrefs() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // One-time migration: any legacy key stored in plain `vdx_prefs` is copied
        // into the encrypted KeyVault and removed from plain prefs. After this,
        // plain prefs are never a read source for keys (see KeyVault).
        KeyVault.migrateFromLegacy(context)

        // Locale-aware: restore the user's preferred language from prefs (set during onboarding).
        val savedLocale = prefs.getString("vdx_locale", null)
        if (!savedLocale.isNullOrBlank()) {
            PromptTemplate.setLocale(savedLocale)
        }

        // API keys now live in the encrypted KeyVault (BYOK). Reads are vault-first;
        // the legacy plain-prefs keys are migration sources only and are drained by
        // KeyVault.migrateFromLegacy above.
        val groqKey = KeyVault.get(context, KeyVault.GROQ)
        val geminiKey = KeyVault.get(context, KeyVault.GEMINI)
        val sarvamKey = KeyVault.get(context, KeyVault.SARVAM)
        val geminiModel = prefs.getString("vdx_llm_model", null) ?: "gemini-3.6-flash"

        if (!groqKey.isNullOrBlank()) groqAsr = GroqAsrEngine(groqKey)
        if (!sarvamKey.isNullOrBlank()) {
            val sarvamModel = prefs.getString("vdx_sarvam_model", null) ?: "saarika:v2.5"
            sarvamAsr = SarvamAsrEngine(sarvamKey, sarvamModel)
        }
        if (!geminiKey.isNullOrBlank()) {
            // Gemini TTS is FALLBACK ONLY (per locked voice-stack decision):
            // system TextToSpeech is the primary spoken-response path. Cloud TTS
            // is constructed here only so speak() can fall back to it when a key
            // is explicitly configured AND system TTS fails.
            val ttsVoice = prefs.getString("vdx_tts_voice", "Kore") ?: "Kore"
            geminiTts = GeminiTtsEngine(
                context = context,
                apiKey = geminiKey,
                model = prefs.getString("vdx_tts_model", "gemini-3.1-flash-tts-preview")
                    ?: "gemini-3.1-flash-tts-preview",
                voiceName = ttsVoice
            )
        }

        // Initialize the WebRTC VAD endpoint. Guarded so a native-load failure on a
        // given device degrades gracefully (vadEndpoint stays null → no gating).
        vadEndpoint = try {
            com.vdx.sonic.voice.vad.VadWebRtc()
        } catch (_: Throwable) {
            Log.w(TAG, "VAD unavailable; proceeding without endpointing")
            null
        }

        cleanupEngine = CleanupEngine(
            apiKey = geminiKey.orEmpty().ifBlank { groqKey.orEmpty() },
            baseUrl = prefs.getString("vdx_cleanup_base_url", "") ?: "",
            model = prefs.getString("vdx_cleanup_model", "qwen2.5:7b") ?: "qwen2.5:7b",
            provider = prefs.getString("vdx_cleanup_provider", "none") ?: "none"
        )

        // Telemetry: engine-selection snapshot once per session. Coarse engine
        // names only; the key field records whether a BYOK key is in the vault
        // (byok) or the install is keyless (none), never the key itself. The
        // ASR is the primary on-device SpeechRecognizer unless a cloud engine's
        // key is configured; if no cloud ASR key is present the app is keyless.
        val asrChooser = when {
            !groqKey.isNullOrBlank() -> "groq"
            !sarvamKey.isNullOrBlank() -> "sarvam"
            else -> "ondevice"
        }
        val hasAnyKey = !groqKey.isNullOrBlank() ||
            !sarvamKey.isNullOrBlank() || !geminiKey.isNullOrBlank()
        // The LLM (intent parsing / reasoning) is regex-based unless a Gemini
        // key is configured (the only LLM-backed path wired today).
        val llmChooser = if (!geminiKey.isNullOrBlank()) "gemini" else "regex"
        Telemetry.logEngineSelectionOnce(
            asr = asrChooser,
            llm = llmChooser,
            key = if (hasAnyKey) "byok" else "none"
        )
    }

    fun configureAsr(apiKey: String, model: String = "whisper-large-v3-turbo") {
        groqAsr = GroqAsrEngine(apiKey, model)
        Telemetry.logEngineSelectionOnce(asr = "groq", llm = null, key = "byok")
    }

    fun configureCleanup(
        apiKey: String = "",
        baseUrl: String = "",
        model: String = "qwen2.5:7b",
        provider: String = "none"
    ) {
        cleanupEngine = CleanupEngine(apiKey, baseUrl, model, provider)
    }

    fun process(capture: CaptureSession) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                if (!diagnostics.isReady()) {
                    val blockers = diagnostics.getBlockers()
                    onStateChange?.invoke(BubbleState.BLOCKED_PERMISSION)
                    onError?.invoke(blockers.firstOrNull() ?: "System not ready")
                    return@launch
                }

                onStateChange?.invoke(BubbleState.PROCESSING)

                if (capture.audioData.isEmpty()) {
                    onStateChange?.invoke(BubbleState.ERROR)
                    onError?.invoke(PromptTemplate.render(PromptTemplate.NO_AUDIO))
                    return@launch
                }

                val asrResult = transcribe(capture)
                if (asrResult.text.isBlank() || asrResult.confidence < 0.3f) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(
                        ClarificationRequest(
                            id = java.util.UUID.randomUUID().toString(),
                            question = PromptTemplate.render(PromptTemplate.DIDNT_CATCH),
                            type = ClarificationType.AMBIGUOUS_INTENT
                        )
                    )
                    return@launch
                }

                onPartialTranscript?.invoke(asrResult.text)

                val cleanedText = cleanup(asrResult.text)

                // DRAFT_NOTE is a memory action — route before readScreen so it works
                // even when accessibility is disabled.
                val earlyIntent = intentParser.parse(cleanedText)
                if (earlyIntent.type == IntentType.DRAFT_NOTE) {
                    routeDraftNote(earlyIntent)
                    return@launch
                }

                val screenModel = harness.readScreen(getAccessibilityService())
                val repairResult = entityRepair.repair(
                    transcript = cleanedText,
                    screenModel = screenModel,
                    vocabulary = getVocabulary(),
                    contacts = getContacts()
                )

                val intent = intentParser.parse(repairResult.repairedText)
                if (handleCancel(intent)) return@launch
                if (gateUnresolvedIr(intent)) return@launch

                val gated = com.vdx.sonic.executor.VoiceSafeActions.enforce(intent)
                if (gated == null) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(
                        ClarificationRequest(
                            id = java.util.UUID.randomUUID().toString(),
                            question = PromptTemplate.render(PromptTemplate.CANT_DO_BY_VOICE),
                            type = ClarificationType.AMBIGUOUS_INTENT,
                            context = intent
                        )
                    )
                    return@launch
                }
                if (blockedByInstallation(gated)) return@launch
                val clarificationRequest = clarification.evaluate(gated, repairResult)
                if (clarificationRequest != null) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(clarificationRequest)
                    return@launch
                }

                val plan = planner.plan(intent, screenModel, harness)
                onStateChange?.invoke(BubbleState.EXECUTING)
                val result = sonicRobot.execute(plan)

                // Record episode for V2 memory
                memoryStore.recordEpisode(
                    goal = intent.rawText,
                    action = intent.type.name.lowercase(),
                    target = intent.entities.values.firstOrNull().orEmpty(),
                    outcome = if (result.isHonestSuccess()) "success" else "failure",
                    errorDetail = (result as? ExecutionResult.Failed)?.reason
                        ?: (result as? ExecutionResult.Blocked)?.reason.orEmpty()
                )

                when (result) {
                    is ExecutionResult.ClarificationNeeded -> {
                        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                        onClarification?.invoke(
                            ClarificationRequest(
                                id = java.util.UUID.randomUUID().toString(),
                                question = result.question,
                                type = ClarificationType.AMBIGUOUS_INTENT,
                                context = result.context
                            )
                        )
                    }
                    is ExecutionResult.ConfirmationNeeded -> {
                        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                        onClarification?.invoke(
                            ClarificationRequest(
                                id = java.util.UUID.randomUUID().toString(),
                                question = result.prompt,
                                type = ClarificationType.ACTION_CONFIRMATION,
                                context = plan.intent
                            )
                        )
                    }
                    is ExecutionResult.Blocked -> {
                        onStateChange?.invoke(BubbleState.ERROR)
                        onResult?.invoke(result)
                    }
                    is ExecutionResult.Unverified -> {
                        // Action completed but side effect not confirmed.
                        // Report honestly to the user — never claim "Done."
                        onStateChange?.invoke(BubbleState.ERROR)
                        speak("I tried to ${result.message}, but I can't confirm it worked. ${result.reason}", Verbosity.MIN_ERROR)
                        onResult?.invoke(result)
                    }
                    else -> {
                        onStateChange?.invoke(
                            if (result.isHonestSuccess()) BubbleState.DONE else BubbleState.ERROR
                        )
                        onResult?.invoke(result)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "process failed", e)
                onStateChange?.invoke(BubbleState.ERROR)
                onError?.invoke(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * External automation entry point (RUN_TASK / RUN_CHAT intents). Submits a
     * task/chat string through the full Sonic pipeline (cleanup → repair → parse
     * → plan → execute), bypassing ASR. Same path as [processText].
     */
    fun submitTask(task: String) {
        processText(task)
    }

    /** True when Groq or Sarvam BYOK is configured — bubble may capture PCM. */
    fun hasCloudAsr(): Boolean = groqAsr != null || sarvamAsr != null

    private fun gateUnresolvedIr(intent: SonicIntent): Boolean {
        val surface = IntentIrV1.firstUnresolvedSurface(intent) ?: return false
        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
        onClarification?.invoke(
            ClarificationRequest(
                id = java.util.UUID.randomUUID().toString(),
                question = "Which one — $surface?",
                type = ClarificationType.AMBIGUOUS_INTENT,
                context = intent
            )
        )
        return true
    }

    /**
     * A projected capability that fails the local intersection does not reach
     * execution. Unprojected intents stay on the existing path.
     */
    private fun blockedByInstallation(gated: com.vdx.sonic.SonicIntent): Boolean {
        val decision = com.vdx.capability.InstallationPlane.blockIfProjected(gated.type) ?: return false
        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
        onClarification?.invoke(
            ClarificationRequest(
                id = java.util.UUID.randomUUID().toString(),
                question = decision.userMessage(),
                type = ClarificationType.ACTION_CONFIRMATION,
                context = gated
            )
        )
        return true
    }

    /** Process typed/transcript text through cleanup → repair → plan → execute (no ASR). */
    fun processText(text: String) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                onStateChange?.invoke(BubbleState.PROCESSING)
                val cleaned = cleanup(text)

                // DRAFT_NOTE is a memory action — it does NOT need the screen/accessibility.
                // Route it to the Memory-to-Action slice BEFORE readScreen, so it works even
                // when accessibility is disabled. Parse from the cleaned text directly.
                val earlyIntent = intentParser.parse(cleaned)
                if (earlyIntent.type == IntentType.DRAFT_NOTE) {
                    routeDraftNote(earlyIntent)
                    return@launch
                }

                val screenModel = harness.readScreen(getAccessibilityService())
                val repairResult = entityRepair.repair(
                    transcript = cleaned,
                    screenModel = screenModel,
                    vocabulary = getVocabulary(),
                    contacts = getContacts()
                )
                val intent = intentParser.parse(repairResult.repairedText)
                if (handleCancel(intent)) return@launch
                if (gateUnresolvedIr(intent)) return@launch

                val gated = com.vdx.sonic.executor.VoiceSafeActions.enforce(intent)
                if (gated == null) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(
                        ClarificationRequest(
                            id = java.util.UUID.randomUUID().toString(),
                            question = PromptTemplate.render(PromptTemplate.CANT_DO_BY_VOICE),
                            type = ClarificationType.AMBIGUOUS_INTENT,
                            context = intent
                        )
                    )
                    return@launch
                }
                if (blockedByInstallation(gated)) return@launch
                val clarificationRequest = clarification.evaluate(gated, repairResult)
                if (clarificationRequest != null) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(clarificationRequest)
                    return@launch
                }
                val plan = planner.plan(intent, screenModel, harness)
                onStateChange?.invoke(BubbleState.EXECUTING)
                val result = sonicRobot.execute(plan)
                onStateChange?.invoke(
                    if (result.isHonestSuccess()) BubbleState.DONE else BubbleState.ERROR
                )
                onResult?.invoke(result)
            } catch (e: Exception) {
                Log.e("SonicEngine", "processText failed", e)
                onStateChange?.invoke(BubbleState.ERROR)
                onError?.invoke(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Route a DRAFT_NOTE intent to the Memory-to-Action slice: propose → confirm.
     * Shared by all entry points so the screen/accessibility is never required.
     */
    private suspend fun routeDraftNote(intent: SonicIntent) {
        val proposal = memoryToAction.propose(
            transcript = intent.rawText,
            sessionId = sessionId,
            userId = "user"
        )
        pendingProposal = proposal
        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
        onClarification?.invoke(
            ClarificationRequest(
                id = java.util.UUID.randomUUID().toString(),
                question = PromptTemplate.render(
                    PromptTemplate.NOTE_CONFIRM,
                    mapOf("body" to (proposal.intent.entities["body"] ?: proposal.transcript))
                ),
                type = ClarificationType.ACTION_CONFIRMATION,
                context = intent
            )
        )
    }

    /**
     * Process text from Google SpeechRecognizer through the full Sonic pipeline
     * with ASR confidence gating, diagnostics, cleanup, entity repair, intent parse,
     * plan, execute, and memory logging — same path as raw-audio process().
     */
    fun processFromText(text: String, asrResult: AsrResult) {
        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                if (!diagnostics.isReady()) {
                    val blockers = diagnostics.getBlockers()
                    onStateChange?.invoke(BubbleState.BLOCKED_PERMISSION)
                    onError?.invoke(blockers.firstOrNull() ?: "System not ready")
                    return@launch
                }

                onStateChange?.invoke(BubbleState.PROCESSING)

                if (asrResult.text.isBlank() || asrResult.confidence < 0.3f) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(
                        ClarificationRequest(
                            id = java.util.UUID.randomUUID().toString(),
                            question = PromptTemplate.render(PromptTemplate.DIDNT_CATCH),
                            type = ClarificationType.AMBIGUOUS_INTENT
                        )
                    )
                    return@launch
                }

                onPartialTranscript?.invoke(asrResult.text)

                val cleanedText = cleanup(text)

                // DRAFT_NOTE is a memory action — route before readScreen so it works
                // even when accessibility is disabled.
                val earlyIntent = intentParser.parse(cleanedText)
                if (earlyIntent.type == IntentType.DRAFT_NOTE) {
                    routeDraftNote(earlyIntent)
                    return@launch
                }

                val screenModel = harness.readScreen(getAccessibilityService())
                val repairResult = entityRepair.repair(
                    transcript = cleanedText,
                    screenModel = screenModel,
                    vocabulary = getVocabulary(),
                    contacts = getContacts()
                )

                val intent = intentParser.parse(repairResult.repairedText)
                if (handleCancel(intent)) return@launch
                if (gateUnresolvedIr(intent)) return@launch

                val gated = com.vdx.sonic.executor.VoiceSafeActions.enforce(intent)
                if (gated == null) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(
                        ClarificationRequest(
                            id = java.util.UUID.randomUUID().toString(),
                            question = PromptTemplate.render(PromptTemplate.CANT_DO_BY_VOICE),
                            type = ClarificationType.AMBIGUOUS_INTENT,
                            context = intent
                        )
                    )
                    return@launch
                }
                if (blockedByInstallation(gated)) return@launch
                val clarificationRequest = clarification.evaluate(gated, repairResult)
                if (clarificationRequest != null) {
                    onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                    onClarification?.invoke(clarificationRequest)
                    return@launch
                }

                val plan = planner.plan(intent, screenModel, harness)
                onStateChange?.invoke(BubbleState.EXECUTING)
                val result = sonicRobot.execute(plan)

                // Record episode for V2 memory
                memoryStore.recordEpisode(
                    goal = intent.rawText,
                    action = intent.type.name.lowercase(),
                    target = intent.entities.values.firstOrNull().orEmpty(),
                    outcome = if (result.isHonestSuccess()) "success" else "failure",
                    errorDetail = (result as? ExecutionResult.Failed)?.reason
                        ?: (result as? ExecutionResult.Blocked)?.reason.orEmpty()
                )

                when (result) {
                    is ExecutionResult.ClarificationNeeded -> {
                        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                        onClarification?.invoke(
                            ClarificationRequest(
                                id = java.util.UUID.randomUUID().toString(),
                                question = result.question,
                                type = ClarificationType.AMBIGUOUS_INTENT,
                                context = result.context
                            )
                        )
                    }
                    is ExecutionResult.ConfirmationNeeded -> {
                        onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
                        onClarification?.invoke(
                            ClarificationRequest(
                                id = java.util.UUID.randomUUID().toString(),
                                question = result.prompt,
                                type = ClarificationType.ACTION_CONFIRMATION,
                                context = plan.intent
                            )
                        )
                    }
                    is ExecutionResult.Blocked -> {
                        onStateChange?.invoke(BubbleState.ERROR)
                        onResult?.invoke(result)
                    }
                    else -> {
                        onStateChange?.invoke(
                            if (result.isHonestSuccess()) BubbleState.DONE else BubbleState.ERROR
                        )
                        onResult?.invoke(result)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "processFromText failed", e)
                onStateChange?.invoke(BubbleState.ERROR)
                onError?.invoke(e.message ?: "Unknown error")
            }
        }
    }

    fun handleClarificationResponse(request: ClarificationRequest, response: String) {
        val base = request.context ?: return
        Log.d(TAG, "handleClarificationResponse: pendingProposal=${pendingProposal != null}, baseType=${base.type}, response=\"$response\"")

        // DRAFT_NOTE confirmation: the pending proposal is the source of truth.
        // On "yes", mint the token (live path) and execute through the slice.
        val pending = pendingProposal
        if (pending != null && base.type == IntentType.DRAFT_NOTE) {
            val lower = response.lowercase().trim()
            if (lower.startsWith("yes") || lower.startsWith("y")) {
                pendingProposal = null
                val token = memoryToAction.confirmProposal(pending)
                scope.launch {
                    val result = memoryToAction.execute(pending, token)
                    onStateChange?.invoke(
                        when (result) {
                            is MemoryToActionPipeline.SliceResult.Saved -> BubbleState.DONE
                            else -> BubbleState.ERROR
                        }
                    )
                    onResult?.invoke(
                        when (result) {
                            is MemoryToActionPipeline.SliceResult.Saved ->
                                ExecutionResult.Success("Draft saved: ${result.body.take(40)}", 1)
                            is MemoryToActionPipeline.SliceResult.Rejected ->
                                ExecutionResult.Failed(result.reason, recoverable = false)
                            is MemoryToActionPipeline.SliceResult.Cancelled ->
                                ExecutionResult.Cancelled(result.reason)
                            is MemoryToActionPipeline.SliceResult.Failed ->
                                ExecutionResult.Failed(result.reason, recoverable = false)
                        }
                    )
                }
            } else {
                pendingProposal = null
                onStateChange?.invoke(BubbleState.DONE)
                onResult?.invoke(ExecutionResult.Cancelled(PromptTemplate.render(PromptTemplate.NOTE_CANCELLED)))
            }
            return
        }

        val updatedIntent = clarification.handleResponse(request, response, base)
        if (updatedIntent.type == IntentType.UNKNOWN) {
            onStateChange?.invoke(BubbleState.CLARIFICATION_REQUIRED)
            onClarification?.invoke(
                ClarificationRequest(
                    id = java.util.UUID.randomUUID().toString(),
                    question = updatedIntent.clarificationQuestion ?: PromptTemplate.render(PromptTemplate.WHAT_NEXT),
                    type = ClarificationType.AMBIGUOUS_INTENT
                )
            )
            return
        }
        scope.launch {
            val screenModel = harness.readScreen(getAccessibilityService())
            val plan = planner.plan(updatedIntent, screenModel, harness)
            onStateChange?.invoke(BubbleState.EXECUTING)
            val result = sonicRobot.execute(plan)
            onStateChange?.invoke(
                if (result.isHonestSuccess()) BubbleState.DONE else BubbleState.ERROR
            )
            onResult?.invoke(result)
        }
    }

    /**
     * Speak [text] aloud. Per Cody's locked voice-stack decision:
     *   1. Android system TextToSpeech is PRIMARY (on-device, no API keys).
     *   2. GeminiTtsEngine (cloud) is FALLBACK ONLY — used only when a key is
     *      explicitly configured AND system TTS is unavailable or fails.
     *
     * The default path (no API keys configured) is fully on-device.
     */
    fun speak(text: String, minLevel: Int = Verbosity.MIN_STANDARD) {
        // SINGLE choke point for all TTS. At SILENT (0) short-circuit before any engine init.
        val decision = VerbosityFilter.decide(minLevel, Verbosity.level(context))
        if (!decision.spoken) return
        val gemini = geminiTts
        // PRIMARY: system TextToSpeech. Try it first; it is always available on-device.
        if (trySystemSpeak(text)) return
        // FALLBACK: cloud TTS — only when a key is explicitly configured AND system
        // TTS could not deliver the utterance (unavailable / init failed).
        if (gemini != null) {
            scope.launch {
                try {
                    gemini.speak(text)
                } catch (_: Exception) {
                    // Both paths failed — nothing more to do without hardware audio.
                }
            }
        }
    }

    /**
     * Attempt to speak via Android system TextToSpeech. Returns true if the
     * utterance was queued to the system engine, false if system TTS is
     * unavailable or failed to initialise (caller may then fall back to cloud).
     */
    private fun trySystemSpeak(text: String): Boolean {
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Locale-aware TTS: set language based on the active PromptTemplate locale.
                    val ttsLocale = PromptTemplate.ttsLocale()
                    tts?.language = ttsLocale
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sonic_utterance")
                }
            }
            // TextToSpeech init is async. Optimistically return true so the
            // caller does not race to cloud before the system engine has had a
            // chance — the callback speaks once init completes. If init fails
            // (status != SUCCESS) the callback skips speak and the next call
            // re-initialises.
            return true
        }
        return try {
            // Re-assert the locale in case it changed since init.
            tts?.language = PromptTemplate.ttsLocale()
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sonic_utterance")
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Handle a CANCEL intent (voice-flow abort): abort the
     * in-flight command before any action runs. Cancels the current coroutine job,
     * stops any in-flight speech, and reports a cancelled outcome. Returns true if
     * the caller should short-circuit (stop processing this utterance).
     */
    private fun handleCancel(intent: SonicIntent): Boolean {
        if (intent.type != IntentType.CANCEL) return false
        currentJob?.cancel()
        tts?.stop()
        geminiTts?.stop()
        onStateChange?.invoke(BubbleState.DONE)
        onResult?.invoke(ExecutionResult.Cancelled(PromptTemplate.render(PromptTemplate.CANCELLED)))
        return true
    }

    fun destroy() {
        currentJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /**
     * Transcribe a raw-PCM [CaptureSession]. Per Cody's locked voice-stack
     * decision, the PRIMARY STT path is Android's on-device SpeechRecognizer,
     * which delivers text directly via [processFromText] (no raw-PCM capture).
     * This method is the FALLBACK path for raw-PCM captures (e.g. background mic
     * without the SpeechRecognizer intent). Cloud ASR engines (Groq, OpenAI,
     * Gemini) are themselves fallback-only: each is tried only when its API key
     * is explicitly configured, so the default no-keys install never touches
     * the cloud. If no engine is configured or all return empty, the result is
     * an empty AsrResult, and the caller surfaces a clarification prompt.
     */
    private suspend fun transcribe(capture: CaptureSession): AsrResult {
        // VAD gate: if the captured PCM holds no real speech (silence / background
        // noise / too-short), discard it gracefully instead of letting a cloud
        // Whisper engine hallucinate a short phrase. If VAD is unavailable, fall
        // through to ASR as before (behavior-preserving for that degraded case).
        val vad = vadEndpoint
        if (vad != null) {
            val verdict = try {
                vad.analyze(capture.audioData)
            } catch (_: Exception) {
                null // native classifier unavailable — skip gating, do not crash
            }
            if (verdict != null && verdict == com.vdx.sonic.voice.vad.VadEndpointDetector.VadResult.IDLE) {
                Log.w(TAG, "VAD gate: no speech in capture, discarding")
                return AsrResult(text = "", confidence = 0.0f, provider = "vad")
            }
        }

        // Cloud ASR engines — FALLBACK ONLY, each gated on an explicit API key.
        // Default no-keys install: all null → returns empty → caller clarifies.
        // ASR chain is Groq → Sarvam (OpenAI/Gemini ASR removed per founder decision).
        groqAsr?.transcribe(capture.audioData)?.takeIf { it.text.isNotBlank() }?.let { return it }
        sarvamAsr?.transcribe(capture.audioData)?.takeIf { it.text.isNotBlank() }?.let { return it }
        Log.w(TAG, "No ASR provider configured or all failed (on-device SpeechRecognizer is primary)")
        return AsrResult(text = "", confidence = 0.0f, provider = "none")
    }

    private suspend fun cleanup(text: String): String {
        // FAST PATH: local cleanup is instant and offline. Only route to the LLM
        // when an API key is explicitly configured — the 20-second cloud timeout
        // on every utterance turned every command into multi-second "processing".
        // Local cleanup handles filler/caps/punctuation for all V1 commands.
        val local = LocalCleanupEngine.clean(text)
        val engine = cleanupEngine
        val hasKey = try {
            (engine != null) && engine.hasConfiguredKey()
        } catch (e: Exception) { false }
        if (engine != null && hasKey && local.isNotBlank()) {
            return try {
                engine.cleanup(local).cleanedText.ifBlank { local }
            } catch (_: Exception) {
                local
            }
        }
        return local.ifBlank { text }
    }

    private fun getAccessibilityService(): android.accessibilityservice.AccessibilityService? {
        return com.vdx.VdxAccessibilityService.instance
    }

    private suspend fun getVocabulary(): List<String>? = withContext(Dispatchers.IO) {
        try {
            memoryStore.getVocabularyAliases()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun getContacts(): List<String>? = withContext(Dispatchers.IO) {
        try {
            val names = mutableListOf<String>()
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts.DISPLAY_NAME} IS NOT NULL",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )
            cursor?.use {
                val idx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                var n = 0
                while (it.moveToNext() && n < 500) {
                    if (idx >= 0) {
                        val name = it.getString(idx)?.trim().orEmpty()
                        if (name.length >= 2) {
                            names.add(name)
                            n++
                        }
                    }
                }
            }
            // Merge remembered contact names from memory graph
            memoryStore.getByType("contact").forEach { names.add(it.name) }
            names.distinct().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
