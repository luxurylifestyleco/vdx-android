package com.vdx.capability

/**
 * Versioned device profile. Fields the probe did not return stay null.
 * This object does not invent hardware, permissions, or installed apps.
 */
data class DeviceCapabilityProfile(
    val deviceId: String?,
    val platform: String?,
    val platformVersion: String?,
    val apiLevel: Int?,
    val manufacturer: String?,
    val model: String?,
    val architecture: String?,
    val ramBytes: Long?,
    val storageBytes: Long?,
    val cpu: String?,
    val gpu: String?,
    val sensors: Map<String, Boolean?>,
    val osCapabilities: Map<String, Boolean?>,
    val installedApplications: List<String>?,
    val runtime: RuntimeState,
    val profileVersion: Int,
    val generatedAt: Long,
) {
    fun ref(): String {
        val platformPart = platform ?: "unknown"
        val versionPart = platformVersion ?: "unknown"
        val apiPart = apiLevel?.toString() ?: "unknown"
        return "$platformPart:$versionPart:$apiPart:v$profileVersion"
    }
}

/** What a probe actually observed. Null means not observed, not false. */
data class ProbeSnapshot(
    val deviceId: String? = null,
    val platform: String? = null,
    val platformVersion: String? = null,
    val apiLevel: Int? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val architecture: String? = null,
    val ramBytes: Long? = null,
    val storageBytes: Long? = null,
    val cpu: String? = null,
    val gpu: String? = null,
    val sensors: Map<String, Boolean?> = emptyMap(),
    val osCapabilities: Map<String, Boolean?> = emptyMap(),
    val installedApplications: List<String>? = null,
    val runtime: RuntimeState = RuntimeState(),
)

object DeviceCapabilityDiscovery {
    const val PROFILE_VERSION = 1

    fun from(probe: ProbeSnapshot, generatedAt: Long): DeviceCapabilityProfile {
        return DeviceCapabilityProfile(
            deviceId = probe.deviceId,
            platform = probe.platform,
            platformVersion = probe.platformVersion,
            apiLevel = probe.apiLevel,
            manufacturer = probe.manufacturer,
            model = probe.model,
            architecture = probe.architecture,
            ramBytes = probe.ramBytes,
            storageBytes = probe.storageBytes,
            cpu = probe.cpu,
            gpu = probe.gpu,
            sensors = probe.sensors,
            osCapabilities = probe.osCapabilities,
            installedApplications = probe.installedApplications,
            runtime = probe.runtime,
            profileVersion = PROFILE_VERSION,
            generatedAt = generatedAt,
        )
    }
}
