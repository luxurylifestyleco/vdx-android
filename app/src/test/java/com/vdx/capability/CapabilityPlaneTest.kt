package com.vdx.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPlaneTest {
    private fun installed(id: String, supported: Boolean = true, reason: String? = null): LocalCapability {
        return LocalCapability(
            capabilityId = id,
            applicationId = "app",
            version = "1.0.0",
            enabled = true,
            authorized = true,
            deviceSupported = supported,
            unsupportedReason = reason,
            currentlyAvailable = true,
            executionTarget = "device",
            schemaRef = "schema:$id",
            source = "projection",
        )
    }

    @Test
    fun unsupportedDeviceIsIdentifiedBeforeAnyExecutionFlag() {
        val registry = LocalCapabilityRegistry()
        registry.upsert(installed("voice.background", supported = false, reason = "OS capability unavailable"))
        val decision = CapabilityNegotiator.negotiate("voice.background", registry, RuntimeState())
        assertFalse(decision.executable)
        assertEquals(FailureCode.DEVICE_UNSUPPORTED, decision.code)
        assertEquals("OS capability unavailable", decision.reason)
    }

    @Test
    fun installProjectsIntoLocalRegistry() {
        val registry = LocalCapabilityRegistry()
        assertFalse(registry.contains("mail.send"))
        registry.upsert(installed("mail.send"))
        assertTrue(registry.contains("mail.send"))
        assertTrue(CapabilityNegotiator.negotiate("mail.send", registry, RuntimeState()).executable)
    }

    @Test
    fun enablementIsLocal() {
        val registry = LocalCapabilityRegistry()
        registry.upsert(installed("mail.send").copy(enabled = false))
        val decision = CapabilityNegotiator.negotiate("mail.send", registry, RuntimeState())
        assertEquals(FailureCode.CAPABILITY_DISABLED, decision.code)
        registry.upsert(installed("mail.send").copy(enabled = true))
        assertTrue(CapabilityNegotiator.negotiate("mail.send", registry, RuntimeState()).executable)
    }

    @Test
    fun revocationPreventsExecution() {
        val registry = LocalCapabilityRegistry()
        registry.upsert(installed("mail.send"))
        registry.applyRevocation("mail.send")
        val decision = CapabilityNegotiator.negotiate("mail.send", registry, RuntimeState())
        assertEquals(FailureCode.CAPABILITY_REVOKED, decision.code)
        assertFalse(decision.executable)
    }

    @Test
    fun discoveryDoesNotInventMissingFields() {
        val profile = DeviceCapabilityDiscovery.from(
            ProbeSnapshot(platform = "android", platformVersion = "14", apiLevel = 34, gpu = null),
            generatedAt = 1L,
        )
        assertNull(profile.gpu)
        assertNull(profile.cpu)
        assertNull(profile.ramBytes)
        assertEquals("android", profile.platform)
        assertEquals(34, profile.apiLevel)
    }

    @Test
    fun differentOsVersionsProduceDifferentProfiles() {
        val old = DeviceCapabilityDiscovery.from(
            ProbeSnapshot(platform = "android", platformVersion = "10", apiLevel = 29),
            generatedAt = 1L,
        )
        val newer = DeviceCapabilityDiscovery.from(
            ProbeSnapshot(platform = "android", platformVersion = "14", apiLevel = 34),
            generatedAt = 2L,
        )
        assertTrue(old.ref() != newer.ref())
        val registry = LocalCapabilityRegistry()
        registry.upsert(
            installed("voice.background", supported = false, reason = "OS capability unavailable")
                .copy(osUnsupported = true),
        )
        assertEquals(
            FailureCode.OS_UNSUPPORTED,
            CapabilityNegotiator.negotiate("voice.background", registry, RuntimeState()).code,
        )
    }

    @Test
    fun alternativeIsOfferedWhenPrimaryIsUnsupported() {
        val registry = LocalCapabilityRegistry()
        registry.upsert(installed("transport.request_ride", supported = false, reason = "background execution unavailable"))
        registry.upsert(installed("transport.request_other"))
        val decision = CapabilityNegotiator.negotiate("transport.request_ride", registry, RuntimeState())
        assertEquals("transport.request_other", decision.alternative)
        assertEquals(Recovery.SELECT_ALTERNATIVE, decision.recovery)
    }

    @Test
    fun inferenceDoesNotBecomeAnExplicitStatement() {
        val book = PreferenceBook()
        book.upsert(
            UserPreference(
                preferenceId = "transport.provider",
                userId = "local",
                domain = "transport",
                subject = "preferred_provider",
                value = "ride-a",
                source = PreferenceSource.REPEATED_BEHAVIOR,
                confidence = 0.91,
                scope = "local",
                createdAt = 1L,
                updatedAt = 1L,
                lastObserved = 1L,
            ),
        )
        val stored = book.get("transport.provider")!!
        assertFalse(stored.isExplicit())
        assertEquals(PreferenceSource.REPEATED_BEHAVIOR, stored.source)
    }

    @Test
    fun projectionDoesNotCarryTheFullPreferenceBook() {
        val registry = LocalCapabilityRegistry()
        registry.upsert(installed("mail.send"))
        val book = listOf(
            UserPreference("p1", "local", "mail", "tone", "short", PreferenceSource.EXPLICIT_USER_STATEMENT, 1.0, "local", 1, 1, 1),
            UserPreference("p2", "local", "transport", "provider", "ride-a", PreferenceSource.REPEATED_BEHAVIOR, 0.91, "local", 1, 1, 1),
        )
        val projection = IntentProjectionBuilder.build(
            correlationId = "c1",
            timestamp = 10L,
            intentRaw = "send mail",
            intentNormalized = "mail.send",
            profile = DeviceCapabilityDiscovery.from(ProbeSnapshot(platform = "android", deviceId = "d1"), 10L),
            registry = registry,
            preferences = book,
            domain = "mail",
        )
        assertEquals(listOf("p1"), projection.relevantPreferences.map { it["preference_id"] })
        assertTrue(projection.forbiddenKeysPresent(mapOf("user_memories" to emptyList<String>())).contains("user_memories"))
        assertEquals(listOf("mail.send"), projection.installedRefs)
    }

    @Test
    fun unprojectedIntentDoesNotBlockTheLegacyPath() {
        InstallationPlane.resetForTests()
        assertNull(InstallationPlane.blockIfProjected(com.vdx.sonic.IntentType.CALL))
    }

    @Test
    fun projectedUnsupportedIntentBlocks() {
        InstallationPlane.resetForTests()
        InstallationPlane.registry.upsert(
            installed("phone.place_call", supported = false, reason = "OS capability unavailable"),
        )
        val decision = InstallationPlane.blockIfProjected(com.vdx.sonic.IntentType.CALL)
        assertEquals(FailureCode.DEVICE_UNSUPPORTED, decision?.code)
    }
}
