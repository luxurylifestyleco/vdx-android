package com.vdx.capability

/**
 * A preference is not a fact. An inference never becomes an explicit statement
 * by being stored. Promotion requires a new explicit write.
 */
data class UserPreference(
    val preferenceId: String,
    val userId: String,
    val domain: String,
    val subject: String,
    val value: String,
    val source: PreferenceSource,
    val confidence: Double,
    val scope: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastObserved: Long,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be 0..1" }
    }

    fun isExplicit(): Boolean = source == PreferenceSource.EXPLICIT_USER_STATEMENT
}

class PreferenceBook {
    private val items = linkedMapOf<String, UserPreference>()

    fun upsert(preference: UserPreference): UserPreference {
        val existing = items[preference.preferenceId]
        if (existing != null &&
            existing.source == PreferenceSource.EXPLICIT_USER_STATEMENT &&
            preference.source != PreferenceSource.EXPLICIT_USER_STATEMENT
        ) {
            throw IllegalArgumentException("inference cannot overwrite an explicit preference")
        }
        items[preference.preferenceId] = preference
        return preference
    }

    fun get(preferenceId: String): UserPreference? = items[preferenceId]

    fun relevant(domain: String): List<UserPreference> =
        items.values.filter { it.domain == domain }

    fun all(): List<UserPreference> = items.values.toList()
}

/**
 * Minimum execution context. Refuses a full user model or a full device dump.
 */
data class IntentExecutionProjection(
    val correlationId: String,
    val timestamp: Long,
    val protocolVersion: String,
    val intentRaw: String?,
    val intentNormalized: String?,
    val deviceId: String?,
    val platform: String?,
    val platformVersion: String?,
    val capabilityProfileRef: String?,
    val installedRefs: List<String>,
    val enabledRefs: List<String>,
    val availableRefs: List<String>,
    val relevantPreferences: List<Map<String, Any?>>,
    val activeGoals: List<String>,
    val network: Boolean?,
) {
    fun forbiddenKeysPresent(payload: Map<String, Any?>): List<String> {
        val forbidden = setOf(
            "user_memories",
            "device_database",
            "crm",
            "analytics",
            "credentials",
            "execution_history",
        )
        return payload.keys.filter { it in forbidden }
    }
}

object IntentProjectionBuilder {
    const val PROTOCOL_VERSION = "1.0"

    fun build(
        correlationId: String,
        timestamp: Long,
        intentRaw: String?,
        intentNormalized: String?,
        profile: DeviceCapabilityProfile?,
        registry: LocalCapabilityRegistry,
        preferences: List<UserPreference>,
        domain: String?,
        activeGoals: List<String> = emptyList(),
    ): IntentExecutionProjection {
        val relevant = if (domain == null) emptyList() else preferences.filter { it.domain == domain }
        return IntentExecutionProjection(
            correlationId = correlationId,
            timestamp = timestamp,
            protocolVersion = PROTOCOL_VERSION,
            intentRaw = intentRaw,
            intentNormalized = intentNormalized,
            deviceId = profile?.deviceId,
            platform = profile?.platform,
            platformVersion = profile?.platformVersion,
            capabilityProfileRef = profile?.ref(),
            installedRefs = registry.list().filter { it.installed }.map { it.capabilityId },
            enabledRefs = registry.list().filter { it.enabled }.map { it.capabilityId },
            availableRefs = registry.list().filter { it.currentlyAvailable && it.authorized && !it.revoked }
                .map { it.capabilityId },
            relevantPreferences = relevant.map {
                mapOf(
                    "preference_id" to it.preferenceId,
                    "domain" to it.domain,
                    "subject" to it.subject,
                    "value" to it.value,
                    "source" to it.source.name,
                    "confidence" to it.confidence,
                )
            },
            activeGoals = activeGoals,
            network = profile?.runtime?.networkAvailable,
        )
    }
}
