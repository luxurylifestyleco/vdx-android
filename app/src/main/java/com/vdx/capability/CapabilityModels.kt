package com.vdx.capability

/**
 * Capability states are not interchangeable.
 * EXISTS does not mean EXECUTABLE. INSTALLED does not mean AUTHORIZED.
 */
enum class CapabilityState {
    EXISTS,
    DISCOVERABLE,
    INSTALLABLE,
    INSTALLED,
    ENABLED,
    AUTHORIZED,
    SUPPORTED,
    EXECUTABLE,
    AVAILABLE,
}

enum class FailureCode {
    CAPABILITY_NOT_FOUND,
    CAPABILITY_NOT_INSTALLED,
    CAPABILITY_DISABLED,
    AUTHORIZATION_REQUIRED,
    AUTHORIZATION_EXPIRED,
    CAPABILITY_REVOKED,
    DEVICE_UNSUPPORTED,
    OS_UNSUPPORTED,
    PERMISSION_MISSING,
    RUNTIME_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    TOOL_UNAVAILABLE,
    SCHEMA_INCOMPATIBLE,
    PLATFORM_INCOMPATIBLE,
}

enum class Recovery {
    RETRY,
    REQUEST_PERMISSION,
    INSTALL,
    SELECT_ALTERNATIVE,
    ASK_USER,
    DEGRADE,
    REPORT_IMPOSSIBILITY,
}

enum class PreferenceSource {
    EXPLICIT_USER_STATEMENT,
    REPEATED_BEHAVIOR,
    SINGLE_BEHAVIOR,
    IMPORTED_PROFILE,
    APPLICATION_SIGNAL,
    SYSTEM_INFERENCE,
}

data class LocalCapability(
    val capabilityId: String,
    val applicationId: String,
    val version: String,
    val enabled: Boolean,
    val authorized: Boolean,
    val authorizationExpired: Boolean = false,
    val revoked: Boolean = false,
    val deviceSupported: Boolean,
    val osUnsupported: Boolean = false,
    val unsupportedReason: String? = null,
    val currentlyAvailable: Boolean,
    val permissions: List<String> = emptyList(),
    val missingPermissions: List<String> = emptyList(),
    val executionTarget: String,
    val schemaRef: String,
    val source: String,
    val requiresNetwork: Boolean = false,
    val exists: Boolean = true,
    val installed: Boolean = true,
)

data class RuntimeState(
    val networkAvailable: Boolean? = null,
    val batteryLow: Boolean? = null,
    val memoryPressure: Boolean? = null,
    val executionRestricted: Boolean = false,
)

data class NegotiationDecision(
    val capabilityId: String,
    val executable: Boolean,
    val code: FailureCode? = null,
    val reason: String = "",
    val alternative: String? = null,
    val recovery: Recovery? = null,
) {
    fun userMessage(): String {
        val base = if (reason.isBlank()) code?.name ?: "unavailable" else reason
        return if (alternative != null) "$base. Alternative: $alternative" else base
    }
}
