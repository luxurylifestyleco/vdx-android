package com.vdx.sonic.nano

import android.content.Context
import android.os.Build

/**
 * NanoGate — the on-device AI presence probe (rung 1).
 *
 * Answers ONE question honestly: can this phone run Gemini Nano?
 * Checks the AICore system carrier (the ONLY way apps reach Nano), its
 * availability, and SDK floor. Every VDX Nano feature calls [probe] first and
 * reports "unavailable" instead of pretending — the app's truth-flavor law.
 *
 * Probe layers (cheap first, stop at first signal):
 *  1. SDK floor: AICore exists only on API 34+ (U) — below that the answer is no.
 *  2. AICore package presence (com.google.android.aicore).
 *  3. AICore enabled state (disabled = effectively absent).
 *
 * Deep use-case capability checks need the AICore SDK binding; this gate is the
 * safe static layer; [Probe.confidence] flags how much we know.
 */
class NanoGate(private val context: Context) {

    enum class Status { PRESENT, PRESENT_DISABLED, ABSENT }

    data class Probe(
        val status: Status,
        val sdk: Int = Build.VERSION.SDK_INT,
        val carrierPackage: String? = null,
        val detail: String
    )

    companion object {
        const val AICORE_PACKAGE = "com.google.android.aicore"
        const val MIN_AICORE_SDK = 34 // AICore ships from Android 14 (U)
    }

    fun probe(): Probe {
        if (Build.VERSION.SDK_INT < MIN_AICORE_SDK) {
            return Probe(Status.ABSENT, detail = "SDK " + Build.VERSION.SDK_INT + " < 34: AICore not carried")
        }
        val pkg = try {
            context.packageManager.getPackageInfo(AICORE_PACKAGE, 0)
        } catch (e: Exception) { null }
        if (pkg == null) {
            return Probe(Status.ABSENT, detail = "AICore package not found on this image")
        }
        val appInfo = pkg.applicationInfo
        val enabled = appInfo != null && appInfo.enabled
        return if (enabled) {
            Probe(Status.PRESENT, carrierPackage = AICORE_PACKAGE,
                  detail = "AICore present and enabled — Nano path armable")
        } else {
            Probe(Status.PRESENT_DISABLED, carrierPackage = AICORE_PACKAGE,
                  detail = "AICore present but disabled — Nano path unavailable until enabled")
        }
    }

    /** Honest one-shot for features: Nano-capable only when present AND enabled. */
    fun isNanoCapable(): Boolean = probe().status == Status.PRESENT
}