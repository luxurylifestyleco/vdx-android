package com.vdx.capability

import com.vdx.sonic.IntentType

/**
 * Process-local installation plane. Hot path reads this. It does not query
 * the App Store. A capability that was never projected does not block the
 * existing voice path. A projected capability that fails intersection does.
 */
object InstallationPlane {
    @Volatile
    var registry: LocalCapabilityRegistry = LocalCapabilityRegistry()

    @Volatile
    var profile: DeviceCapabilityProfile? = null

    @Volatile
    var runtime: RuntimeState = RuntimeState()

    @Volatile
    var preferences: PreferenceBook = PreferenceBook()

    fun resetForTests() {
        registry = LocalCapabilityRegistry()
        profile = null
        runtime = RuntimeState()
        preferences = PreferenceBook()
    }

    fun remember(snapshot: ProbeSnapshot, generatedAt: Long) {
        profile = DeviceCapabilityDiscovery.from(snapshot, generatedAt)
        runtime = snapshot.runtime
    }

    /**
     * Returns a blocking decision only when this capability is in the local
     * registry and is not executable. Unprojected intents return null.
     */
    fun blockIfProjected(type: IntentType): NegotiationDecision? {
        val capabilityId = CapabilityIds.forIntent(type) ?: return null
        if (!registry.contains(capabilityId)) return null
        val decision = CapabilityNegotiator.negotiate(capabilityId, registry, runtime)
        return if (decision.executable) null else decision
    }
}
