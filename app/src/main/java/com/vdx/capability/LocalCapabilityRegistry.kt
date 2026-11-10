package com.vdx.capability

/**
 * Local projection of what this installation can do.
 * Not the App Store database. Lookup is in-memory.
 */
class LocalCapabilityRegistry {
    private val entries = linkedMapOf<String, LocalCapability>()

    fun contains(capabilityId: String): Boolean = entries.containsKey(capabilityId)

    fun get(capabilityId: String): LocalCapability? = entries[capabilityId]

    fun list(): List<LocalCapability> = entries.values.toList()

    fun upsert(entry: LocalCapability) {
        entries[entry.capabilityId] = entry
    }

    fun remove(capabilityId: String) {
        entries.remove(capabilityId)
    }

    fun applyRevocation(capabilityId: String): LocalCapability? {
        val current = entries[capabilityId] ?: return null
        val revoked = current.copy(
            revoked = true,
            authorized = false,
            currentlyAvailable = false,
        )
        entries[capabilityId] = revoked
        return revoked
    }

    fun clear() {
        entries.clear()
    }
}

object CapabilityIds {
    /** Maps an intent type to a capability id. Null means this path is not projected. */
    fun forIntent(type: com.vdx.sonic.IntentType): String? = when (type) {
        com.vdx.sonic.IntentType.CALL,
        com.vdx.sonic.IntentType.CONTACT_MANAGE -> "phone.place_call"
        com.vdx.sonic.IntentType.SMS -> "messaging.sms"
        com.vdx.sonic.IntentType.WHATSAPP -> "messaging.send"
        com.vdx.sonic.IntentType.EMAIL -> "mail.send"
        com.vdx.sonic.IntentType.BOOK_RIDE -> "transport.request_ride"
        com.vdx.sonic.IntentType.APP_LAUNCH,
        com.vdx.sonic.IntentType.APP_SWITCH -> "device.open_application"
        com.vdx.sonic.IntentType.SEARCH -> "web.search"
        com.vdx.sonic.IntentType.YOUTUBE_SEARCH,
        com.vdx.sonic.IntentType.YOUTUBE_CONTROL -> "media.play"
        com.vdx.sonic.IntentType.PLAY_STORE -> "store.open"
        com.vdx.sonic.IntentType.SETTINGS_NAVIGATION,
        com.vdx.sonic.IntentType.SYSTEM_TOGGLE -> "device.settings"
        else -> null
    }
}
