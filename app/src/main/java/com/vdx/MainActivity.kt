package com.vdx

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vdx.logging.SessionLogger
import com.vdx.settings.KeyVault
import com.vdx.settings.Verbosity
import com.vdx.sonic.SonicEngine
import com.vdx.sonic.onboarding.VoiceOnboarding
import com.vdx.telemetry.Telemetry
import com.vdx.telemetry.TelemetryEventTypes
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var permissionDetail: TextView
    private lateinit var textInput: EditText
    private lateinit var suggestList: LinearLayout

    // Harness views
    private lateinit var harnessLog: TextView
    private lateinit var btnTestYoutube: Button
    private lateinit var btnTestDialer: Button
    private lateinit var btnTestSettings: Button
    private lateinit var btnTestReadscreen: Button
    private lateinit var btnTestReadsms: Button
    private lateinit var btnTestCall: Button
    private lateinit var btnTestWhatsApp: Button
    private lateinit var btnTestUber: Button
    private lateinit var btnTestSms: Button
    private lateinit var btnTestEmail: Button
    private lateinit var btnTestAll: Button
    private lateinit var btnClearLog: Button
    // Demo driver (outside-request path)
    private lateinit var demoInput: EditText
    private lateinit var btnDemoSend: Button
    private lateinit var btnDemoClear: Button
    private lateinit var demoEvidence: TextView

    // Structured logger for harness sessions
    private var harnessLogger: SessionLogger? = null

    // ponytail: single SessionMemory instance — old code created a new one each time,
    // always returning 0 items.
    private val sessionMemory = SessionMemory()

    private val overlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        actionButton = findViewById(R.id.btn_action)
        accessibilityButton = findViewById(R.id.btn_accessibility)
        permissionDetail = findViewById(R.id.permission_detail)
        textInput = findViewById(R.id.text_input)
        suggestList = findViewById(R.id.suggest_list)

        // Harness views
        harnessLog = findViewById(R.id.harness_log)
        btnTestYoutube = findViewById(R.id.btn_test_youtube)
        btnTestDialer = findViewById(R.id.btn_test_dialer)
        btnTestSettings = findViewById(R.id.btn_test_settings)
        btnTestReadscreen = findViewById(R.id.btn_test_readscreen)
        btnTestReadsms = findViewById(R.id.btn_test_readsms)
        btnTestCall = findViewById(R.id.btn_test_call)
        btnTestWhatsApp = findViewById(R.id.btn_test_whatsapp)
        btnTestUber = findViewById(R.id.btn_test_uber)
        btnTestSms = findViewById(R.id.btn_test_sms)
        btnTestEmail = findViewById(R.id.btn_test_email)
        btnTestAll = findViewById(R.id.btn_test_all)
        btnClearLog = findViewById(R.id.btn_clear_log)
        demoInput = findViewById(R.id.demo_input)
        btnDemoSend = findViewById(R.id.btn_demo_send)
        btnDemoClear = findViewById(R.id.btn_demo_clear)
        demoEvidence = findViewById(R.id.demo_evidence)
        btnDemoSend.setOnClickListener { sendDemoRequest() }
        btnDemoClear.setOnClickListener { demoEvidence.text = "" }

        // Text input — type an intent and press send
        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = textInput.text.toString().trim()
                if (text.isNotBlank()) {
                    processTextIntent(text)
                    textInput.text.clear()
                }
                true
            } else false
        }
        textInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderSuggestions(s?.toString().orEmpty())
            }
        })
        renderSuggestions("")

        updateStatus()
        // First-run does not steal the mic. Suggestions are the live surface.
        // Mic is asked on first bubble tap.

        // One remaining setup card — never a stack, never Stop Bubble.
        actionButton.setOnClickListener {
            when {
                !hasOverlayPermission() -> {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermission.launch(intent)
                }
                !isAccessibilityEnabled() -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            updateStatus()
        }

        accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        accessibilityButton.setOnLongClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            true
        }

        // ════════════════════════════════════════════════════════════════
        // DEVELOPER HARNESS — RobotHand Direct Execution
        // ════════════════════════════════════════════════════════════════
        // Each button invokes RobotHand.execute() with a hardcoded intent,
        // bypassing Bubble, STT, and LLM entirely.
        // Results are logged to harnessLog, logcat, and structured JSON log.

        btnTestYoutube.setOnClickListener { runHarnessTest("YouTube", VdxIntent.YouTube("cat videos")) }
        btnTestDialer.setOnClickListener { runHarnessTest("Dialer", VdxIntent.AppLaunch("Phone")) }
        btnTestSettings.setOnClickListener { runHarnessTest("Settings", VdxIntent.AppLaunch("Settings")) }
        btnTestReadscreen.setOnClickListener { runHarnessTest("Read Screen", VdxIntent.ReadScreen) }
        btnTestReadsms.setOnClickListener { runHarnessTest("Read SMS", VdxIntent.ReadSms) }
        btnTestCall.setOnClickListener { runHarnessTest("Call", VdxIntent.Call("555-1234")) }
        btnTestWhatsApp.setOnClickListener { runHarnessTest("WhatsApp", VdxIntent.WhatsApp("Mom", "Hello from VDX test")) }
        btnTestUber.setOnClickListener { runHarnessTest("Uber", VdxIntent.Uber("airport")) }
        btnTestSms.setOnClickListener { runHarnessTest("SMS", VdxIntent.Sms("John", "Test message")) }
        btnTestEmail.setOnClickListener { runHarnessTest("Email", VdxIntent.Email("test@example.com", "Test email body")) }

        // Run all tests sequentially
        btnTestAll.setOnClickListener {
            appendHarnessLog("══════ RUNNING ALL TESTS ══════")
            val testCases = listOf(
                "Call" to VdxIntent.Call("555-1234"),
                "SMS" to VdxIntent.Sms("John", "Test message"),
                "YouTube" to VdxIntent.YouTube("cat videos"),
                "Dialer" to VdxIntent.AppLaunch("Phone"),
                "Settings" to VdxIntent.AppLaunch("Settings"),
                "Read Screen" to VdxIntent.ReadScreen,
                "Read SMS" to VdxIntent.ReadSms,
                "WhatsApp" to VdxIntent.WhatsApp("Mom", "Hello from VDX test"),
                "Uber" to VdxIntent.Uber("airport"),
                "Email" to VdxIntent.Email("test@example.com", "Test email body")
            )
            runAllTests(testCases)
        }

        btnClearLog.setOnClickListener {
            harnessLog.text = ""
        }

        findViewById<View>(R.id.title_vdx).setOnLongClickListener {
            val labs = findViewById<View>(R.id.labs_section)
            labs.visibility = if (labs.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }

        buildAiKeysSection()
        buildSpeechLevelSection()
        maybeShowTelemetryConsentDialog()
    }

    /**
     * Opt-in telemetry consent, shown once on the *second* app open (never the
     * first, so setup isn't interrupted). Plain-language, default OFF. Counts
     * which kinds of actions happened — never who you called or what was said.
     * consent_granted / consent_denied are the only telemetry events recorded
     * from this gate.
     */
    private fun maybeShowTelemetryConsentDialog() {
        // Skip if the user already decided (either way) on a prior session.
        if (Telemetry.hasConsentDecision(this)) return
        val p = getSharedPreferences("vdx_telemetry_prefs", MODE_PRIVATE)
        val opens = p.getInt("app_open_count", 0) + 1
        p.edit().putInt("app_open_count", opens).apply()
        if (opens < 2) return
        AlertDialog.Builder(this)
            .setTitle("Help improve VDX?")
            .setMessage(
                "VDX counts which actions happen — like that a call was made — " +
                "and sends those counts to help make VDX better. It never sends " +
                "who you call, what you say, or any of your content. " +
                "This is opt-in and off until you turn it on."
            )
            .setPositiveButton("Yes, share usage") { _, _ ->
                Telemetry.setConsent(this, granted = true)
            }
            .setNegativeButton("No, keep it local") { _, _ ->
                Telemetry.setConsent(this, granted = false)
            }
            .setCancelable(false)
            .show()
    }

    private var onboarding: VoiceOnboarding? = null

    /**
     * Voice-first onboarding: check if the user has been onboarded. If not,
     * and the required permissions (mic) are granted, start the voice-driven
     * setup flow. If permissions aren't granted yet, defer — the user will
     * grant permissions via the action button, and onboarding will trigger
     * on the next resume.
     */
    private fun checkOnboarding() {
        if (!hasAudioPermission()) return
        // onCreate + onResume both call this. A live instance must not be
        // restarted, and isOnboarded()==false must not fall through to start().
        if (onboarding != null) return
        val probe = VoiceOnboarding(this)
        if (probe.isOnboarded()) return
        probe.onComplete = {
            runOnUiThread {
                Toast.makeText(this, "VDX setup complete!", Toast.LENGTH_LONG).show()
                updateStatus()
            }
        }
        this.onboarding = probe
        probe.start()
    }

    private fun startBubbleService() {
        val intent = Intent(this, BubbleForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopBubbleService() {
        stopService(Intent(this, BubbleForegroundService::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        onboarding?.destroy()
        onboarding = null
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val overlay = hasOverlayPermission()
        val bubbleRunning = BubbleForegroundService.isRunning
        val accessibilityOn = isAccessibilityEnabled()

        permissionDetail.visibility = View.GONE
        accessibilityButton.visibility = View.GONE

        when {
            !overlay -> {
                statusText.visibility = View.VISIBLE
                statusText.text = "Allow VDX to draw over other apps."
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Allow overlay"
            }
            !accessibilityOn -> {
                statusText.visibility = View.VISIBLE
                statusText.text = "Turn on both VDX rows in Accessibility."
                actionButton.visibility = View.VISIBLE
                actionButton.text = "Open Accessibility"
            }
            else -> {
                statusText.visibility = View.GONE
                actionButton.visibility = View.GONE
                if (!bubbleRunning) startBubbleService()
            }
        }
    }

    private fun renderSuggestions(query: String) {
        suggestList.removeAllViews()
        val pad = (14 * resources.displayMetrics.density).toInt()
        for (cmd in CommandSuggest.suggest(query)) {
            val row = TextView(this).apply {
                text = cmd
                textSize = 16f
                setTextColor(0xFF1A1A2E.toInt())
                setBackgroundColor(0xFFFFFFFF.toInt())
                setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    textInput.setText(cmd)
                    textInput.setSelection(cmd.length)
                    processTextIntent(cmd)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (4 * resources.displayMetrics.density).toInt()
            suggestList.addView(row, lp)
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun processTextIntent(text: String) {
        runSpoken(text)
    }

    // ════ DEMO DRIVER (the Alexa-shaped outside request) ═══════════════
    // Sends the phrase through DemoDriver (same production gates), shows
    // the returned evidence JSON live in the demo panel.
    private fun sendDemoRequest() {
        val phrase = demoInput.text.toString().trim()
        if (phrase.isEmpty()) {
            Toast.makeText(this, "Type a request first", Toast.LENGTH_SHORT).show()
            return
        }
        val id = com.vdx.demo.DemoDriver.sendCommand(this, phrase, "Alexa (demo)")
        demoEvidence.text = "Request $id sent — running through the safety gates…"
        demoInput.setText("")
        com.vdx.demo.DemoDriver.addListener { rec ->
            if (rec.id == id) {
                runOnUiThread {
                    demoEvidence.text = com.vdx.demo.DemoDriver.evidenceJson(rec)
                }
            }
        }
    }

    private fun runSpoken(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        try {
            SonicEngine(this).processText(text)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Could not run", Toast.LENGTH_LONG).show()
        }
        updateStatus()
    }

    // ════ BYOK AI keys (Labs) ════════════════════════════════════════
    // One secure per-provider input + Save. Keys go into the encrypted KeyVault.
    // Save validates the endpoint with a live ping first; the raw key is never
    // logged or shown in a toast — only "Gemini key valid" etc.

    private data class AiProvider(
        val label: String,        // display label e.g. "Gemini"
        val vaultKey: String,     // KeyVault constant
        val displayName: String   // log label (provider name)
    )

    private val aiProviders = listOf(
        AiProvider("Gemini", KeyVault.GEMINI, "Gemini"),
        AiProvider("OpenAI", KeyVault.OPENAI, "OpenAI"),
        AiProvider("Groq", KeyVault.GROQ, "Groq"),
        AiProvider("Sarvam", KeyVault.SARVAM, "Sarvam")
    )

    private fun buildAiKeysSection() {
        val root = findViewById<LinearLayout>(R.id.ai_keys_section) ?: return
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val rowPad = (14 * density).toInt()

        for (provider in aiProviders) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(rowPad, rowPad, rowPad, (8 * density).toInt())
                setBackgroundColor(0xFFFFFFFF.toInt())
            }

            val label = EditText(this).apply {
                setTextColor(0xFF1A1A2E.toInt())
                textSize = 13f
                isFocusable = false
                setPadding(pad, 0, pad, 0)
                showSoftInputOnFocus = false
            }
            // Set the provider label without triggering a text watcher.
            label.setText(provider.label)
            // Password-ish hint field for the key (masked when stored text present).
            val input = EditText(this).apply {
                setTextColor(0xFF1A1A2E.toInt())
                textSize = 13f
                hint = "Paste ${provider.label} API key…"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setPadding(pad, 0, pad, 0)
            }

            // Pre-fill with any existing vaulted key (masked via password input type).
            input.setText(KeyVault.get(this@MainActivity, provider.vaultKey).orEmpty())

            val status = TextView(this).apply {
                textSize = 11f
                text = ""
                setPadding(pad, 0, pad, 0)
            }

            val save = Button(this).apply {
                text = "Save ${provider.label}"
                setTextColor(0xFF6C3AED.toInt())
                isAllCaps = false
                background = null
                setOnClickListener { saveAiKey(provider, input, status) }
            }

            row.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(save, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ((40 * density).toInt())))

            root.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * density).toInt()
            })
        }
    }

    // ════ Accessibility verbosity dial (Labs) ════════════════════════════
    // One horizontal row of 11 tappable pips (0..10), current selection highlighted,
    // with a plain-language caption per band. Persists immediately on tap — no save
    // button. Also logs an essential `verbosity_changed` telemetry event.
    private fun buildSpeechLevelSection() {
        val root = findViewById<LinearLayout>(R.id.speech_level_section) ?: return
        root.removeAllViews()
        val density = resources.displayMetrics.density

        val pipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Spread 11 pips evenly; each is square and tappable.
            setPadding(0, 0, 0, 0)
        }
        val pipViews = Array(Verbosity.MAX + 1) { index ->
            TextView(this).apply {
                text = index.toString()
                gravity = android.view.Gravity.CENTER
                textSize = 13f
                setTextColor(0xFF1A1A2E.toInt())
                setBackgroundColor(0xFFEEEEF2.toInt())
            }.also { pip ->
                pip.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                pip.setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                pipsContainer.addView(pip)
            }
        }
        pipsContainer.apply {
            // Give the container a fixed height so pips render square-ish.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ((36 * density).toInt())
            )
        }

        // Caption label reflecting the current band.
        val caption = TextView(this).apply {
            text = Verbosity.caption(Verbosity.level(this@MainActivity))
            textSize = 12f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, (8 * density).toInt(), 0, 0)
        }

        fun applySelection() {
            val current = Verbosity.level(this@MainActivity)
            for ((i, pip) in pipViews.withIndex()) {
                pip.setBackgroundColor(if (i == current) 0xFF6C3AED.toInt() else 0xFFEEEEF2.toInt())
                pip.setTextColor(if (i == current) 0xFFFFFFFF.toInt() else 0xFF1A1A2E.toInt())
            }
            caption.text = Verbosity.caption(current)
        }
        applySelection()

        for ((i, pip) in pipViews.withIndex()) {
            pip.setOnClickListener {
                Verbosity.set(this@MainActivity, i)
                applySelection()
                // Essential telemetry: which level the accessibility dial moved to.
                Telemetry.log(
                    TelemetryEventTypes.VERBOSITY_CHANGED,
                    mapOf("level" to i)
                )
            }
        }

        root.addView(pipsContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(caption, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun saveAiKey(provider: AiProvider, input: EditText, status: TextView) {
        val value = input.text.toString().trim()
        if (value.isBlank()) {
            status.text = ""
            KeyVault.put(this, provider.vaultKey, null)
            Toast.makeText(this, "${provider.label} key removed", Toast.LENGTH_SHORT).show()
            return
        }
        if (!KeyVault.isAvailable(this)) {
            status.text = "✗ encrypted storage unavailable"
            status.setTextColor(0xFFDC2626.toInt())
            return
        }
        status.text = "validating…"
        status.setTextColor(0xFFF59E0B.toInt())
        // Network + validation off the UI thread; never print the key.
        Thread {
            val result = validateProviderKey(provider, value)
            runOnUiThread {
                when (result) {
                    is ValidationResult.Valid -> {
                        KeyVault.put(this, provider.vaultKey, value)
                        status.text = "✓ ${provider.label} key valid — saved"
                        status.setTextColor(0xFF16A34A.toInt())
                        Log.i("AiKeys", "${provider.displayName} key valid")
                        Telemetry.log(
                            com.vdx.telemetry.TelemetryEventTypes.KEY_ADDED,
                            mapOf("provider" to provider.displayName.lowercase(), "valid" to true)
                        )
                    }
                    is ValidationResult.Invalid -> {
                        status.text = "✗ ${provider.label} key rejected (401/403)"
                        status.setTextColor(0xFFDC2626.toInt())
                        Log.w("AiKeys", "${provider.displayName} key rejected")
                    }
                    is ValidationResult.EndpointReachable -> {
                        // 400/422 etc — endpoint reachable, key accepted structurally.
                        KeyVault.put(this, provider.vaultKey, value)
                        status.text = "✓ ${provider.label} endpoint reachable — saved"
                        status.setTextColor(0xFF16A34A.toInt())
                        Log.i("AiKeys", "${provider.displayName} key valid (endpoint reachable)")
                        Telemetry.log(
                            com.vdx.telemetry.TelemetryEventTypes.KEY_ADDED,
                            mapOf("provider" to provider.displayName.lowercase(), "valid" to true)
                        )
                    }
                    is ValidationResult.Unreachable -> {
                        status.text = "✗ ${provider.label}: network unreachable (not saved)"
                        status.setTextColor(0xFFDC2626.toInt())
                        Log.w("AiKeys", "${provider.displayName} key validation: network unreachable")
                    }
                }
            }
        }.start()
    }

    private sealed interface ValidationResult {
        object Valid : ValidationResult
        object Invalid : ValidationResult
        object EndpointReachable : ValidationResult
        object Unreachable : ValidationResult
    }

    /** Ping the provider endpoint to confirm the key works before storing it. */
    private fun validateProviderKey(provider: AiProvider, key: String): ValidationResult {
        return try {
            when (provider.displayName) {
                "Gemini" -> pingGet(
                    "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
                )
                "OpenAI" -> pingGet(
                    "https://api.openai.com/v1/models", key
                )
                "Groq" -> pingGet(
                    "https://api.groq.com/openai/v1/models", key
                )
                "Sarvam" -> pingSarvam(key)
                else -> ValidationResult.EndpointReachable
            }
        } catch (_: Exception) {
            ValidationResult.Unreachable
        }
    }

    private fun pingGet(urlStr: String, bearer: String? = null): ValidationResult {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            val code = conn.responseCode
            when {
                code == 401 || code == 403 -> ValidationResult.Invalid
                code in 200..299 -> ValidationResult.Valid
                code == 400 -> ValidationResult.Invalid
                else -> ValidationResult.EndpointReachable
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun pingSarvam(key: String): ValidationResult {
        val conn = URL("https://api.sarvam.ai/speech-to-text").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=probe")
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.doOutput = true
            conn.outputStream.use { it.write("--probe--\r\n".toByteArray()) }
            val code = conn.responseCode
            when {
                code == 401 || code == 403 -> ValidationResult.Invalid
                code in 200..299 -> ValidationResult.Valid
                code == 400 -> ValidationResult.Invalid
                else -> ValidationResult.EndpointReachable
            }
        } catch (_: Exception) {
            ValidationResult.Unreachable
        } finally {
            conn.disconnect()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Harness: Run RobotHand with a hardcoded intent on a background
    // thread, then log the result to the harness log, logcat, and
    // structured JSON log.
    // ════════════════════════════════════════════════════════════════

    private fun runHarnessTest(label: String, intent: VdxIntent) {
        val robotHand = RobotHand(this, null)
        appendHarnessLog("▶ $label — executing RobotHand...")

        // Initialize structured logger for this test run
        val sessionId = "harness-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
        val logger = SessionLogger.create(this, sessionId)
        harnessLogger = logger

        logger.log("HARNESS_START", mapOf<String, Any?>(
            "label" to label,
            "intent" to (intent::class.simpleName ?: "Unknown"),
            "intent_data" to intent.toString()
        ))

        Thread {
            val startMs = System.currentTimeMillis()
            try {
                val result = robotHand.execute(intent)
                val elapsed = System.currentTimeMillis() - startMs
                logger.log("HARNESS_RESULT", mapOf(
                    "label" to label,
                    "status" to "success",
                    "result" to result,
                    "duration_ms" to elapsed
                ))
                runOnUiThread {
                    appendHarnessLog("✓ $label → ${result.take(120)} (${elapsed}ms)")
                    Toast.makeText(this, "$label: ${result.take(80)}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startMs
                logger.logError("HARNESS_FAILURE", "${label} failed after ${elapsed}ms", e)
                runOnUiThread {
                    appendHarnessLog("✗ $label FAILED after ${elapsed}ms: ${e.message ?: "unknown error"}")
                    Toast.makeText(this, "$label failed: ${e.message?.take(60) ?: "unknown"}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Run all test cases sequentially on a background thread.
     * Each test gets its own RobotHand instance. Results are logged
     * to structured JSON and the harness log.
     */
    private fun runAllTests(testCases: List<Pair<String, VdxIntent>>) {
        val sessionId = "suite-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}"
        val logger = SessionLogger.create(this, sessionId)
        harnessLogger = logger

        logger.log("SUITE_START", mapOf("test_count" to testCases.size))

        Thread {
            var passed = 0
            var failed = 0

            testCases.forEachIndexed { index, (label, intent) ->
                val robotHand = RobotHand(this, null)
                val startMs = System.currentTimeMillis()
                val logPrefix = "[${index + 1}/${testCases.size}]"

                runOnUiThread {
                    appendHarnessLog("$logPrefix $label...")
                }

                try {
                    val result = robotHand.execute(intent)
                    val elapsed = System.currentTimeMillis() - startMs
                    logger.log("TEST_PASS", mapOf<String, Any?>(
                        "label" to label,
                        "intent" to (intent::class.simpleName ?: "Unknown"),
                        "result" to result,
                        "duration_ms" to elapsed
                    ))
                    passed++
                    runOnUiThread {
                        appendHarnessLog("  ✓ $label (${elapsed}ms)")
                    }
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - startMs
                    logger.logError("TEST_FAIL", "${label} failed after ${elapsed}ms", e)
                    failed++
                    runOnUiThread {
                        appendHarnessLog("  ✗ $label FAILED: ${e.message?.take(60) ?: "unknown"} (${elapsed}ms)")
                    }
                }
            }

            logger.log("SUITE_RESULT", mapOf(
                "total" to testCases.size,
                "passed" to passed,
                "failed" to failed
            ))

            runOnUiThread {
                appendHarnessLog("══════ RESULTS: $passed passed, $failed failed, ${testCases.size} total ══════")
                Toast.makeText(this, "Suite: $passed/$passed passed, $failed failed", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun appendHarnessLog(line: String) {
        val current = harnessLog.text.toString()
        val lines = current.split("\n")
        // Keep last 30 lines
        val trimmed = if (lines.size >= 30) lines.drop(lines.size - 29).joinToString("\n") else current
        harnessLog.text = trimmed.trimEnd() + "\n" + line
        // Auto-scroll to bottom
        harnessLog.post { harnessLog.scrollTo(0, harnessLog.lineCount * harnessLog.lineHeight) }
    }
}
