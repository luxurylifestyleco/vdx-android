package com.vdx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.vdx.sonic.*
import com.vdx.settings.Verbosity
import com.vdx.settings.VerbosityFilter
import com.vdx.sonic.overlay.TargetHighlightOverlay
import com.vdx.sonic.voice.DictationInserter
import com.vdx.sonic.voice.PcmMicCapture

/**
 * BubbleForegroundService — Messenger-style floating chat-head bubble.
 *
 * State machine:
 *   IDLE      → purple bubble, slow pulse + halo glow
 *   LISTENING → green bubble, fast waveform pulse
 *   THINKING  → amber bubble, spinning icon
 *   EXECUTING → blue bubble, progress bar + action text overlay
 *   SPEAKING  → blue bubble, slow pulse
 *   ERROR     → red bubble, shake
 *
 * Interactions:
 *   tap       → start/stop mic → SonicEngine pipeline
 *   long-press → diagnostics panel (then keyboard fallback option)
 *
 * Intent routing (live path):
 *   Bubble → VoiceCapture (PCM) → SonicEngine (ASR→Cleanup→Repair→Parse→Plan→Sonic RobotHand)
 *   Text/keyboard fallback → SonicEngine.processText()
 *   Legacy RobotHand retained for MainActivity harness tests only
 */
class BubbleForegroundService : Service() {

    companion object {
        private const val TAG = "VDX"

        var isRunning = false
            private set

        /** Live service instance, set in onCreate / cleared in onDestroy. */
        @Volatile
        var runningInstance: BubbleForegroundService? = null
            private set

        private const val CHANNEL_ID = "vdx_bubble"
        private const val NOTIF_ID = 1

        /** Called by VdxAccessibilityService when a text field gains focus. */
        fun onTextFieldFocused() {
            // future hook; currently the service polls on demand
        }

        /**
         * Screen-reader band (level 9-10): called by VdxAccessibilityService on
         * TYPE_WINDOW_STATE_CHANGED so a new foreground app/screen is announced.
         */
        fun onForegroundWindowChanged(packageName: String, title: String) {
            val instance = runningInstance ?: return
            if (Verbosity.level(instance) >= Verbosity.MIN_SCREEN) {
                instance.announceForeground(packageName, title)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // State Machine
    // ──────────────────────────────────────────────────────────────────────

    enum class BubbleState {
        IDLE, LISTENING, THINKING, EXECUTING, SPEAKING, ERROR,
        // Additional states from Sonic pipeline (mapped 1:1, no lossy conversion)
        CLARIFICATION_REQUIRED, DONE, BLOCKED_PERMISSION
    }

    // ──────────────────────────────────────────────────────────────────────
    // Fields
    // ──────────────────────────────────────────────────────────────────────

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleIcon: ImageView? = null
    private var bubbleContainer: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var haloView: View? = null
    private var progressBar: ProgressBar? = null
    private var textOverlay: View? = null
    private var executingOverlay: TextView? = null
    private var bubbleX = 0
    private var bubbleY = 0

    // GAP 1: visual target-highlight overlay (default OFF). Lazily created once
    // the WindowManager is available so it never interferes with the bubble.
    private var targetHighlight: TargetHighlightOverlay? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false
    /** Re-entry guard for the text-input submit (one Enter can fire onEditorAction twice). */
    private var textSubmitInFlight = false

    // Dictation inserter — lands dictated text into the focused field using the
    // soniqo/speech-android pattern (non-focusable overlay + ACTION_SET_TEXT +
    // clipboard-paste fallback). Lazily created so it never touches the clipboard
    // unless a dictation is actually being inserted.
    private val dictationInserter: DictationInserter by lazy { DictationInserter(this) }
    private val pcmCapture = PcmMicCapture()
    private var usingPcm = false

    // VDX Sonic engine
    private val sonicEngine: SonicEngine by lazy {
        SonicEngine(this).apply {
            onStateChange = { state ->
                handler.post {
                    // Direct 1:1 mapping now that service enum has all Sonic states
                    currentState = when (state) {
                        com.vdx.sonic.BubbleState.IDLE -> BubbleState.IDLE
                        com.vdx.sonic.BubbleState.LISTENING -> BubbleState.LISTENING
                        com.vdx.sonic.BubbleState.PROCESSING -> BubbleState.THINKING
                        com.vdx.sonic.BubbleState.CLARIFICATION_REQUIRED -> BubbleState.CLARIFICATION_REQUIRED
                        com.vdx.sonic.BubbleState.EXECUTING -> BubbleState.EXECUTING
                        com.vdx.sonic.BubbleState.DONE -> BubbleState.DONE
                        com.vdx.sonic.BubbleState.ERROR -> BubbleState.ERROR
                        com.vdx.sonic.BubbleState.BLOCKED_PERMISSION -> BubbleState.BLOCKED_PERMISSION
                    }
                }
            }
            onPartialTranscript = { text ->
                handler.post { showTopToast("... $text") }
            }
            onClarification = { request ->
                handler.post {
                    speak(request.question, Verbosity.MIN_CONFIRM)
                    // Store pending clarification
                    pendingClarification = request
                }
            }
            onResult = { result ->
                handler.post {
                    when (result) {
                        is ExecutionResult.Success -> {
                            showExecutingOverlay(result.message)
                            handler.postDelayed({
                                hideExecutingOverlay()
                                currentState = BubbleState.IDLE
                            }, 2000)
                        }
                        is ExecutionResult.Failed -> {
                            speak(result.reason, Verbosity.MIN_ERROR)
                            currentState = BubbleState.ERROR
                            handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                        }
                        is ExecutionResult.Cancelled -> {
                            currentState = BubbleState.IDLE
                        }
                        else -> {}
                    }
                }
            }
            onError = { message ->
                handler.post {
                    speak(message, Verbosity.MIN_ERROR)
                    currentState = BubbleState.ERROR
                    handler.postDelayed({ currentState = BubbleState.IDLE }, 2000)
                }
            }
        }
    }
    private var pendingClarification: com.vdx.sonic.ClarificationRequest? = null

    private var currentState: BubbleState = BubbleState.IDLE
        set(value) {
            field = value
            handler.post { updateBubbleVisuals() }
        }

    private val handler = Handler(Looper.getMainLooper())
    private var pulseRunnable: Runnable? = null

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: BubbleForegroundService starting")
        isRunning = true
        runningInstance = this
        createNotificationChannel()
        startForeground(
            NOTIF_ID,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        initTTS()
        showBubble()
        Log.d(TAG, "onCreate: service started, bubble should be visible")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: intent=$intent, flags=$flags, startId=$startId")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: cleaning up BubbleForegroundService")
        isRunning = false
        runningInstance = null
        stopPulse()
        hideExecutingOverlay()
        bubbleView?.let { windowManager?.removeView(it) }
        bubbleView = null
        textOverlay?.let { windowManager?.removeView(it) }
        textOverlay = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        // Detach the target-highlight overlay (idempotent, safe even if never shown).
        targetHighlight?.hide()
        targetHighlight = null
        tts?.stop()
        tts?.shutdown()
        if (pcmCapture.isRunning) pcmCapture.stop()
        Log.d(TAG, "onDestroy: service destroyed")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VDX Bubble", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VDX voice assistant bubble"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("VDX")
            .setContentText("Voice assistant active — tap bubble to speak")
            .setSmallIcon(R.drawable.vdx_bubble)
            .setOngoing(true)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────
    // TTS
    // ──────────────────────────────────────────────────────────────────────

    private fun initTTS() {
        Log.d(TAG, "initTTS: initializing TextToSpeech with Google TTS engine")
        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Locale-aware TTS: use the active PromptTemplate locale.
                val ttsLocale = com.vdx.sonic.voice.PromptTemplate.ttsLocale()
                tts?.language = ttsLocale
                // ISSUE 2(c): Audio attributes with USAGE_ASSISTANT and CONTENT_TYPE_SPEECH
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
                Log.d(TAG, "initTTS: TTS ready, language=$ttsLocale, audioAttrs=USAGE_ASSISTANT/CONTENT_TYPE_SPEECH")
            } else {
                Log.e(TAG, "initTTS: TTS init failed, status=$status")
            }
        }, "com.google.android.tts")
    }

    private fun speak(text: String, minLevel: Int = Verbosity.MIN_STANDARD) {
        Log.d(TAG, "speak: \"$text\"")
        // SINGLE choke point for TTS at every level. Visual feedback (the toast) is
        // unconditional — a deaf user reads every message. Only the AUDIO is gated.
        val decision = VerbosityFilter.decide(minLevel, Verbosity.level(this))
        if (decision.spoken) {
            // ISSUE 2(b): Set STREAM_MUSIC to max volume so TTS is loud
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0
            )
            // ISSUE 2(a): Request transient audio focus on STREAM_MUSIC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vdx_utterance")
        }
        showTopToast(text)
    }

    /** ISSUE 3(d): Show a toast at the top of the screen so it doesn't overlap the bubble. */
    private fun showTopToast(text: String) {
        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dpToPx(48))
        toast.show()
        Log.d(TAG, "Toast: $text")
    }

    /**
     * Screen-reader band (level 9-10): speak a one-line summary of the new foreground
     * app + screen title when the accessibility service reports a window change, and
     * mirror it visually via the toast so deaf users see it too.
     */
    fun announceForeground(packageName: String, title: String) {
        val appLabel = friendlyAppName(packageName)
        val summary = buildString {
            append(if (appLabel.isNotBlank()) appLabel else "App opened")
            if (title.isNotBlank() && title != appLabel) append(": $title")
        }
        speak(summary, Verbosity.MIN_SCREEN)
    }

    /** Best-effort human-readable app name from the package token; blank when unknown. */
    private fun friendlyAppName(packageName: String): String {
        if (packageName.isBlank()) return ""
        // Use the installed app's label when resolvable.
        return try {
            packageManager.getApplicationInfo(packageName, 0)?.let {
                packageManager.getApplicationLabel(it).toString()
            } ?: lastPackageSegment(packageName)
        } catch (_: Exception) {
            lastPackageSegment(packageName)
        }
    }

    private fun lastPackageSegment(packageName: String): String =
        packageName.substringAfterLast('.').replaceFirstChar { it.titlecase() }

    // ──────────────────────────────────────────────────────────────────────
    // Bubble View
    // ──────────────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ──────────────────────────────────────────────────────────────────────
    // GAP 1: Target highlight overlay
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Toggle the visual target-highlight overlay that paints subtle outlines over
     * the current screen's actionable elements (clickable / scrollable / editable)
     * so a low-vision user can see where to tap. Default OFF.
     */
    fun toggleTargetHighlight() {
        val wm = windowManager ?: return
        val highlight = targetHighlight ?: TargetHighlightOverlay(this, wm).also {
            targetHighlight = it
        }
        highlight.toggle()
        if (highlight.isVisible()) {
            showTopToast("Target highlights on — tap a highlighted element")
        } else {
            showTopToast("Target highlights off")
        }
    }

    private fun showBubble() {
        Log.d(TAG, "showBubble: creating bubble overlay")
        if (!canDrawOverlays()) {
            Log.w(TAG, "showBubble: overlay permission not granted")
            showTopToast("VDX needs overlay permission")
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // CODY'S DIRECTIVE (2026-10-06): emulate WisprFlow's dimensions + transparency.
        // Old: 80dp blocky pill + 96dp halo — read as a 'persistent square' on real phones.
        // New: compact 44dp circle (still ≥48px tap-target) + whisper-thin 52dp halo
        // + slight window translucency (0.92) — like Wispr Flow's unobtrusive pill.
        val bubbleSize = dpToPx(44)
        val haloSize = dpToPx(52)
        // WisprFlow dock: lower-right edge (thumb zone); vertical center-ish of lower third
        val dm = resources.displayMetrics
        val x = dpToPx(14)
        val y = Math.max(dpToPx(60), (dm.heightPixels * 0.62f).toInt())
        bubbleX = x
        bubbleY = y

        // ISSUE 1(c): Halo — translucent glow circle behind the bubble
        val halo = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(12, 108, 58, 237))
            }
        }
        haloView = halo

        // VDX mic icon (vector drawable)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.vdx_bubble)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.WHITE)
        }
        bubbleIcon = icon

        // Progress bar (hidden by default, shown in THINKING / EXECUTING)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(4)
            )
        }
        progressBar = progress

        // Container: pill with icon + progress stacked vertically
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            addView(icon, LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)))
            addView(progress)
        }
        bubbleContainer = container

        // Apply pill-shaped GradientDrawable background
        applyPillBackground(container, BubbleState.IDLE)
        applyHaloBackground(halo, BubbleState.IDLE)
        container.elevation = dpToPx(8).toFloat()

        // ISSUE 1(c): FrameLayout wrapping halo (behind) + container (front)
        val frame = FrameLayout(this).apply {
            addView(halo, FrameLayout.LayoutParams(haloSize, haloSize, Gravity.CENTER))
            addView(container, FrameLayout.LayoutParams(bubbleSize, bubbleSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            haloSize,
            haloSize,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            alpha = 0.86f   // WisprFlow-style translucency (glassy, readable)
        }
        bubbleParams = params

        // Touch: drag + tap + long-press
        // Uses GestureDetector for tap/long-press and raw touch for drag.
        // This avoids the double-fire issues from a single OnTouchListener
        // that inconsistently handles ACTION_OUTSIDE.
        frame.isClickable = true
        frame.isFocusable = false

        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                onLongPress()
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap()
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                // Must return true to consume the event sequence
                return true
            }
        })

        // Disable long-press timeout from GestureDetector so our 500ms threshold
        // in the drag handler is the sole arbiter. We handle long-press ourselves
        // via the drag handler's heldMs check.
        gestureDetector.setIsLongpressEnabled(false)

        frame.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false
            private var downTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                Log.d(TAG, "onTouch: action=${event.action} x=${event.x} y=${event.y} rawX=${event.rawX} rawY=${event.rawY}")
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                        downTime = SystemClock.uptimeMillis()
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                        // Also feed to gesture detector for tap detection
                        gestureDetector.onTouchEvent(event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) moved = true
                        if (moved) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            bubbleX = params.x
                            bubbleY = params.y
                            windowManager?.updateViewLayout(bubbleView, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        if (!moved) {
                            val heldMs = SystemClock.uptimeMillis() - downTime
                            if (heldMs >= 500) {
                                onLongPress()
                            } else {
                                // Feed to gesture detector for single-tap
                                gestureDetector.onTouchEvent(event)
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // System cancelled the gesture — reset state
                        v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        moved = false
                    }
                    MotionEvent.ACTION_OUTSIDE -> {
                        // Consume but do nothing — prevents synthetic double-fire
                        Log.d(TAG, "onTouch: ACTION_OUTSIDE (consumed)")
                    }
                }
                return true
            }
        })

        bubbleView = frame
        windowManager?.addView(frame, params)
        currentState = BubbleState.IDLE
        // ISSUE 1(e): Log bubble position
        Log.d(TAG, "showBubble: bubble at x=$x y=$y size=$bubbleSize")
        showTopToast("VDX bubble is live — tap to speak, hold to type")
    }

    // ──────────────────────────────────────────────────────────────────────
    // Pill Background — GradientDrawable with rounded corners
    // ──────────────────────────────────────────────────────────────────────

    private fun applyPillBackground(view: View, state: BubbleState) {
        val (startColor, endColor) = stateColors(state)
        // PHONE-TEST FIX (real-device round): the bubble must be a CIRCLE.
        // cornerRadius was fixed at 28dp on an 80dp square box → visibly square
        // (Aman's 'persistent square' + the 83s recording). Radius = half the
        // view's width → perfect circle at any size. View width is used instead
        // of the constant so future sizes stay circular.
        val w = view.width
        val radius = ((if (w > 0) w else dpToPx(80)) / 2f).toFloat()

        val drawable = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            colors = intArrayOf(startColor, endColor)
            this.cornerRadius = radius
            // WisprFlow-style soft edge: no hard stroke; glassy edge via subtle lighter halo ring instead
            setStroke(dpToPx(1), Color.argb(36, 255, 255, 255))
        }
        view.background = drawable
    }

    /** ISSUE 1(c): Apply translucent halo color matching the current state */
    private fun applyHaloBackground(view: View, state: BubbleState) {
        val color = when (state) {
            BubbleState.IDLE      -> Color.argb(40, 108, 58, 237)
            BubbleState.LISTENING -> Color.argb(60, 34, 197, 94)
            BubbleState.THINKING  -> Color.argb(50, 245, 158, 11)
            BubbleState.EXECUTING -> Color.argb(50, 59, 130, 246)
            BubbleState.SPEAKING  -> Color.argb(50, 59, 130, 246)
            BubbleState.ERROR     -> Color.argb(60, 239, 68, 68)
            BubbleState.CLARIFICATION_REQUIRED -> Color.argb(50, 245, 158, 11)
            BubbleState.DONE      -> Color.argb(50, 34, 197, 94)
            BubbleState.BLOCKED_PERMISSION -> Color.argb(60, 239, 68, 68)
        }
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun stateColors(state: BubbleState): Pair<Int, Int> {
        return when (state) {
            BubbleState.IDLE      -> Color.parseColor("#6c3aed") to Color.parseColor("#5b2fd9")
            BubbleState.LISTENING -> Color.parseColor("#22c55e") to Color.parseColor("#16a34a")
            BubbleState.THINKING  -> Color.parseColor("#f59e0b") to Color.parseColor("#d97706")
            BubbleState.EXECUTING -> Color.parseColor("#3b82f6") to Color.parseColor("#2563eb")
            BubbleState.SPEAKING  -> Color.parseColor("#3b82f6") to Color.parseColor("#2563eb")
            BubbleState.ERROR     -> Color.parseColor("#ef4444") to Color.parseColor("#f59e0b")
            BubbleState.CLARIFICATION_REQUIRED -> Color.parseColor("#f59e0b") to Color.parseColor("#d97706")
            BubbleState.DONE      -> Color.parseColor("#22c55e") to Color.parseColor("#16a34a")
            BubbleState.BLOCKED_PERMISSION -> Color.parseColor("#ef4444") to Color.parseColor("#dc2626")
        }
    }

    private fun updateBubbleVisuals() {
        val container = bubbleContainer ?: return
        val progress = progressBar ?: return
        val halo = haloView ?: return

        Log.d(TAG, "updateBubbleVisuals: state=$currentState")

        // Clear previous animations
        stopPulse()
        bubbleIcon?.clearAnimation()

        // Hide executing overlay unless we're in EXECUTING state
        if (currentState != BubbleState.EXECUTING) {
            hideExecutingOverlay()
        }

        // Update background colors
        applyPillBackground(container, currentState)
        applyHaloBackground(halo, currentState)

        // Progress bar visibility
        when (currentState) {
            BubbleState.THINKING, BubbleState.CLARIFICATION_REQUIRED -> {
                progress.isIndeterminate = true
                progress.visibility = View.VISIBLE
            }
            BubbleState.EXECUTING -> {
                progress.isIndeterminate = false
                progress.visibility = View.VISIBLE
                progress.progress = 0
                animateProgress(progress)
            }
            else -> {
                progress.visibility = View.GONE
            }
        }

        // ISSUE 4: State-specific animations
        when (currentState) {
            BubbleState.IDLE -> startIdlePulse()           // (b) slow scale 1.0→1.1→1.0 every 2s
            BubbleState.LISTENING -> { /* onRmsChanged handles real-time scale */ }
            BubbleState.THINKING -> startThinkingSpin()    // (b) spinning progress indicator
            BubbleState.SPEAKING -> startSpeakingPulse()   // (d) blue + slow pulse
            BubbleState.ERROR -> shakeBubble(container)    // (e) red + shake
            BubbleState.CLARIFICATION_REQUIRED -> startThinkingSpin() // amber, same as thinking
            BubbleState.DONE -> { /* brief green flash, handled by onResult callback */ }
            BubbleState.BLOCKED_PERMISSION -> shakeBubble(container)  // red shake
            BubbleState.EXECUTING -> { /* progress bar + text overlay handle visuals */ }
        }
    }

    private fun animateProgress(bar: ProgressBar) {
        handler.post(object : Runnable {
            override fun run() {
                if (bar.progress < 100) {
                    bar.progress += 5
                    handler.postDelayed(this, 50)
                }
            }
        })
    }

    // ──────────────────────────────────────────────────────────────────────
    // Animations
    // ──────────────────────────────────────────────────────────────────────

    /** ISSUE 1(b): IDLE — slow scale 1.0 → 1.1 → 1.0 every 2 seconds */
    private fun startIdlePulse() {
        val container = bubbleContainer ?: return
        val scaleAnim = ScaleAnimation(
            1f, 1.1f, 1f, 1.1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1000
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        container.startAnimation(scaleAnim)
        pulseRunnable = Runnable { container.startAnimation(scaleAnim) }
    }

    /** ISSUE 4(d): SPEAKING — slow gentle pulse (blue color from stateColors) */
    private fun startSpeakingPulse() {
        val container = bubbleContainer ?: return
        val scaleAnim = ScaleAnimation(
            1f, 1.08f, 1f, 1.08f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1500
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        container.startAnimation(scaleAnim)
        pulseRunnable = Runnable { container.startAnimation(scaleAnim) }
    }

    /** ISSUE 4(b): THINKING — spinning progress indicator on the bubble icon */
    private fun startThinkingSpin() {
        val icon = bubbleIcon ?: return
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 800
            repeatMode = Animation.RESTART
            repeatCount = Animation.INFINITE
        }
        icon.startAnimation(rotate)
    }

    private fun stopPulse() {
        // Remove any pending runnable before clearing animation
        pulseRunnable?.let { handler.removeCallbacks(it) }
        pulseRunnable = null
        bubbleContainer?.clearAnimation()
        bubbleIcon?.clearAnimation()
    }

    /** ISSUE 4(e): ERROR — shake briefly (red color from stateColors) */
    private fun shakeBubble(view: View) {
        val shake = android.view.animation.TranslateAnimation(
            0f, dpToPx(6).toFloat(), 0f, 0f
        ).apply {
            duration = 50
            repeatMode = Animation.REVERSE
            repeatCount = 3
        }
        view.startAnimation(shake)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Executing Overlay — ISSUE 4(c): text near the bubble showing what it's doing
    // ──────────────────────────────────────────────────────────────────────

    private fun showExecutingOverlay(text: String) {
        hideExecutingOverlay()
        val tv = TextView(this).apply {
            setText(text)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(200, 0, 0, 0))
                cornerRadius = dpToPx(16).toFloat()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = bubbleX + dpToPx(100)
            this.y = bubbleY
        }
        executingOverlay = tv
        windowManager?.addView(tv, params)
    }

    private fun hideExecutingOverlay() {
        executingOverlay?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "hideExecutingOverlay: failed to remove view", e)
            }
        }
        executingOverlay = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Voice Capture → Android SpeechRecognizer (no API keys, no VoiceCaptureManager)
    // ──────────────────────────────────────────────────────────────────────

    private fun onTap() {
        Log.d(TAG, "onTap: isListening=$isListening usingPcm=$usingPcm")
        if (!isListening) {
            startVoiceCapture()
        } else {
            isListening = false
            currentState = BubbleState.THINKING
            if (usingPcm) stopPcmAndProcess()
            else stopVoiceCaptureAndProcess()
        }
    }

    private fun onLongPress() {
        Log.d(TAG, "onLongPress: showing diagnostics")
        if (isListening) {
            if (usingPcm) pcmCapture.stop()
            else speechRecognizer?.stopListening()
            isListening = false
            usingPcm = false
        }
        showDiagnosticsOverlay()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Voice Capture → Android SpeechRecognizer (no API keys, no VoiceCaptureManager)
    // ──────────────────────────────────────────────────────────────────────

    private fun startVoiceCapture() {
        Log.d(TAG, "startVoiceCapture")
        if (isListening) {
            Log.w(TAG, "startVoiceCapture: already listening, ignoring")
            return
        }

        // BYOK Groq/Sarvam: tap-to-talk PCM → SonicEngine.transcribe (already built).
        // No keys: on-device SpeechRecognizer (Play-clean default).
        if (sonicEngine.hasCloudAsr() && pcmCapture.start()) {
            usingPcm = true
            isListening = true
            currentState = BubbleState.LISTENING
            showTopToast("Listening...")
            Log.d(TAG, "startVoiceCapture: PCM path (Groq/Sarvam)")
            return
        }

        usingPcm = false
        isListening = true
        currentState = BubbleState.LISTENING
        showTopToast("Listening...")
        Log.d(TAG, "startVoiceCapture: using Android SpeechRecognizer")

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        }
        val recognizer = speechRecognizer ?: run {
            Log.e(TAG, "startVoiceCapture: SpeechRecognizer not available")
            isListening = false
            currentState = BubbleState.ERROR
            speak("Speech recognition not available on this device", Verbosity.MIN_ERROR)
            return
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "SR onReadyForSpeech")
            }
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "SR onBeginningOfSpeech")
            }
            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 60f) / 60f).coerceIn(0f, 1f)
                val scaleBoost = normalized * 0.4f
                handler.post {
                    val container = bubbleContainer ?: return@post
                    if (currentState == BubbleState.LISTENING) {
                        val scale = 1f + scaleBoost
                        container.scaleX = scale
                        container.scaleY = scale
                    }
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "SR onEndOfSpeech")
            }
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                    else -> "Error $error"
                }
                Log.e(TAG, "SR onError: $msg")
                handler.post {
                    isListening = false
                    currentState = BubbleState.ERROR
                    speak(msg, Verbosity.MIN_ERROR)
                    handler.postDelayed({
                        currentState = BubbleState.IDLE
                        showTextInputOverlay()
                    }, 800)
                }
            }
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = texts?.firstOrNull()?.trim()
                Log.d(TAG, "SR onResults: \"$transcript\"")
                handler.post {
                    isListening = false
                    if (!transcript.isNullOrBlank()) {
                        currentState = BubbleState.THINKING
                        // Dictation mode: if an editable text field currently has
                        // input focus, the transcript is dictation — insert it into
                        // that field directly (non-focusable overlay + ACTION_SET_TEXT
                        // + clipboard-paste fallback) instead of running it through
                        // the command pipeline. This is the soniqo/speech-android
                        // pattern: the bubble never steals focus, so the target field
                        // keeps it and the text lands reliably.
                        if (com.vdx.VdxAccessibilityService.instance?.findFocusedTextField() != null) {
                            val ok = dictationInserter.insert(transcript)
                            if (ok) {
                                currentState = BubbleState.DONE
                                showTopToast("✓ Dictated")
                                handler.postDelayed({ currentState = BubbleState.IDLE }, 1200)
                            } else {
                                currentState = BubbleState.ERROR
                                speak("Couldn't insert text into the focused field", Verbosity.MIN_ERROR)
                                handler.postDelayed({ currentState = BubbleState.IDLE }, 1200)
                            }
                            return@post
                        }
                        // Wake-word handling: "Hey Vision <command>" → strip the phrase,
                        // process the command hands-free. Pure activation ("Hey Vision")
                        // just re-arms listening for the follow-up command.
                        val wake = com.vdx.sonic.voice.WakeWordDetector.detect(transcript)
                        val command = if (wake.activated) wake.command else transcript
                        if (wake.activated && wake.pureActivation) {
                            currentState = BubbleState.LISTENING
                            showTopToast("🎤 Listening...")
                            speechRecognizer?.startListening(recognizerIntent())
                            return@post
                        }
                        // Wrap Google STT as synthetic ASR result for full pipeline
                        val syntheticResult = com.vdx.sonic.AsrResult(
                            text = command,
                            confidence = 0.85f,
                            provider = "google"
                        )
                        // If a clarification/confirmation is pending, route the spoken
                        // response to it (e.g. "yes" to a DRAFT_NOTE confirmation) rather
                        // than parsing it as a fresh command.
                        val pending = pendingClarification
                        if (pending != null) {
                            pendingClarification = null
                            sonicEngine.handleClarificationResponse(pending, command)
                        } else {
                            sonicEngine.processFromText(command, syntheticResult)
                        }
                    } else {
                        currentState = BubbleState.ERROR
                        speak("I didn't catch that", Verbosity.MIN_ERROR)
                        handler.postDelayed({ currentState = BubbleState.IDLE }, 1000)
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = texts?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    handler.post { showTopToast("... $partial") }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = recognizerIntent()
        recognizer.startListening(intent)
    }

    /** Build the standard SpeechRecognizer intent (shared by first listen + wake-word re-arm). */
    private fun recognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Locale-aware STT: use the active PromptTemplate locale for speech recognition.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, com.vdx.sonic.voice.PromptTemplate.sttLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    /**
     * Second orb tap on the PCM path: stop the recorder and run Groq→Sarvam.
     */
    private fun stopPcmAndProcess() {
        val pcm = pcmCapture.stop()
        usingPcm = false
        if (pcm.isEmpty()) {
            currentState = BubbleState.ERROR
            speak("I didn't catch that", Verbosity.MIN_ERROR)
            handler.postDelayed({ currentState = BubbleState.IDLE }, 1000)
            return
        }
        sonicEngine.process(
            CaptureSession(
                audioData = pcm,
                timestamp = System.currentTimeMillis(),
                foregroundPackage = null,
                focusedFieldState = null,
                uiSnapshot = null
            )
        )
    }

    /**
     * Stop listening. Called when user taps the bubble a second time.
     */
    private fun stopVoiceCaptureAndProcess() {
        Log.d(TAG, "stopVoiceCaptureAndProcess")
        speechRecognizer?.stopListening()
        // If still listening, the onResults callback will fire with whatever was captured.
        // If nothing was captured, onError(ERROR_NO_MATCH) fires instead.
    }

    private fun showDiagnosticsOverlay() {
        textOverlay?.let { windowManager?.removeView(it) }
        currentState = BubbleState.BLOCKED_PERMISSION
        val report = sonicEngine.diagnostics.checkAll()
        val blockers = sonicEngine.diagnostics.getBlockers()
        val summary = buildString {
            append("Diagnostics\n")
            append(if (report.microphone.ok) "✅ Mic\n" else "❌ Mic\n")
            append(if (report.overlay.ok) "✅ Overlay\n" else "❌ Overlay\n")
            append(if (report.accessibility.ok) "✅ Accessibility\n" else "❌ Accessibility\n")
            append(if (report.batteryOptimization.ok) "✅ Battery\n" else "⚠️ Battery\n")
            append(if (report.serviceRunning.ok) "✅ Service\n" else "❌ Service\n")
            append(if (report.harnessReady.ok) "✅ Harness\n" else "❌ Harness\n")
            append(if (report.networkAvailable.ok) "✅ Network\n" else "❌ Network\n")
            if (blockers.isNotEmpty()) {
                append("\nBlocked: ${blockers.joinToString("; ")}")
            }
        }
        speak(if (blockers.isEmpty()) "All systems ready." else blockers.first(), Verbosity.MIN_STANDARD)
        showTopToast(summary.replace("\n", " · "))

        // Offer keyboard fallback after diagnostics
        handler.postDelayed({
            currentState = BubbleState.IDLE
            showTextInputOverlay()
        }, 1500)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Text Input Overlay (keyboard fallback)
    // ──────────────────────────────────────────────────────────────────────

    private fun showTextInputOverlay() {
        Log.d(TAG, "showTextInputOverlay: creating text input overlay")
        // Remove any existing overlay
        textOverlay?.let { windowManager?.removeView(it) }

        // Don't overwrite current state — caller (onError, diagnostics) sets it

        val editText = EditText(this).apply {
            hint = "Type: call mom, whatsapp john, uber to airport..."
            setTextColor(Color.parseColor("#1a1a2e"))
            setHintTextColor(Color.GRAY)
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#f5f5f7"))
                cornerRadius = dpToPx(12).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#6c3aed"))
            }
        }

        val innerPadding = dpToPx(12)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(innerPadding, innerPadding, innerPadding, innerPadding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dpToPx(20).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#e0e0e0"))
            }
            addView(editText)
        }

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.4f
        }

        // We need a dim layer behind, so wrap in a FrameLayout
        val dimView = View(this).apply {
            setBackgroundColor(Color.argb(100, 0, 0, 0))
        }
        val frame = FrameLayout(this).apply {
            addView(dimView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(container, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                marginStart = dpToPx(24)
                marginEnd = dpToPx(24)
            })
        }

        // Tap outside to dismiss
        dimView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                removeTextOverlay()
                currentState = BubbleState.IDLE
            }
            true
        }

        // Submit on Enter
        // Guard against double-fire: one Enter can trigger onEditorAction twice
        // (IME action + key event) ~16ms apart. The flag must persist past the
        // second fire, so it is reset on a delay, not in a finally block.
        editText.setOnEditorActionListener { _, _, _ ->
            if (textSubmitInFlight) return@setOnEditorActionListener true
            textSubmitInFlight = true
            handler.postDelayed({ textSubmitInFlight = false }, 500)
            val text = editText.text.toString().trim()
            removeTextOverlay()
            if (text.isNotBlank()) {
                Log.d(TAG, "text input submitted: \"$text\"")
                showTopToast("Heard: $text")
                currentState = BubbleState.THINKING
                val pending = pendingClarification
                if (pending != null) {
                    pendingClarification = null
                    sonicEngine.handleClarificationResponse(pending, text)
                } else {
                    sonicEngine.processText(text)
                }
            } else {
                currentState = BubbleState.IDLE
            }
            true
        }

        // Tethered hardware / BT keyboard tether — works at EVERY level including 0.
        // Focus lands in this overlay EditText, so USB/BT key events arrive here.
        // Enter submits (the shared double-fire guard protects the path from both
        // the IME action above and a raw KEYCODE_ENTER below); Escape cancels
        // listening without submitting.
        editText.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        // Let the IME editor-action path handle submission when the
                        // IME action is active; only drive submission directly for a
                        // hardware Enter when the IME is not the source.
                        if (event.getRepeatCount() == 0 && !textSubmitInFlight) {
                            textSubmitInFlight = true
                            handler.postDelayed({ textSubmitInFlight = false }, 500)
                            val text = editText.text.toString().trim()
                            removeTextOverlay()
                            if (text.isNotBlank()) {
                                Log.d(TAG, "hardware key submit: \"$text\"")
                                showTopToast("Heard: $text")
                                currentState = BubbleState.THINKING
                                val pending = pendingClarification
                                if (pending != null) {
                                    pendingClarification = null
                                    sonicEngine.handleClarificationResponse(pending, text)
                                } else {
                                    sonicEngine.processText(text)
                                }
                            } else {
                                currentState = BubbleState.IDLE
                            }
                        }
                        true
                    }
                    android.view.KeyEvent.KEYCODE_ESCAPE -> {
                        Log.d(TAG, "hardware key: Escape cancels")
                        removeTextOverlay()
                        currentState = BubbleState.IDLE
                        true
                    }
                    else -> false
                }
            } else {
                // Consume the DOWN of keys we handle on UP so they never leak through.
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_ESCAPE -> true
                    else -> false
                }
            }
        }

        textOverlay = frame
        windowManager?.addView(frame, overlayParams)
        editText.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, 0)
    }

    private fun removeTextOverlay() {
        textOverlay?.let {
            windowManager?.removeView(it)
            textOverlay = null
        }
    }
}