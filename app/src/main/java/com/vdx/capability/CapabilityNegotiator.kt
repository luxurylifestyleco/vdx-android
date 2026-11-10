package com.vdx.capability

/**
 * Intersection check owned by VDX: installation ∩ device ∩ current runtime.
 * Does not call the App Store. Does not ask Elastic Web what this device is.
 */
object CapabilityNegotiator {
    fun negotiate(
        capabilityId: String,
        registry: LocalCapabilityRegistry,
        runtime: RuntimeState,
        allowAlternative: Boolean = true,
    ): NegotiationDecision {
        val entry = registry.get(capabilityId)
            ?: return fail(capabilityId, FailureCode.CAPABILITY_NOT_FOUND, "not in the local registry")
        if (!entry.exists) {
            return fail(capabilityId, FailureCode.CAPABILITY_NOT_FOUND, "capability does not exist")
        }
        if (!entry.installed) {
            return fail(capabilityId, FailureCode.CAPABILITY_NOT_INSTALLED, "capability is not installed")
        }
        if (!entry.enabled) {
            return fail(capabilityId, FailureCode.CAPABILITY_DISABLED, "capability is disabled")
        }
        if (entry.revoked) {
            return fail(capabilityId, FailureCode.CAPABILITY_REVOKED, "authorization was revoked")
        }
        if (entry.authorizationExpired) {
            return fail(capabilityId, FailureCode.AUTHORIZATION_EXPIRED, "authorization expired")
        }
        if (!entry.authorized) {
            return fail(capabilityId, FailureCode.AUTHORIZATION_REQUIRED, "authorization required")
        }
        if (!entry.deviceSupported) {
            val reason = entry.unsupportedReason ?: "device or OS cannot execute this capability"
            val code = if (entry.osUnsupported) FailureCode.OS_UNSUPPORTED else FailureCode.DEVICE_UNSUPPORTED
            val alternative = if (allowAlternative) alternativeFor(capabilityId, registry) else null
            return fail(capabilityId, code, reason, alternative)
        }
        if (entry.missingPermissions.isNotEmpty()) {
            return fail(
                capabilityId,
                FailureCode.PERMISSION_MISSING,
                "missing permissions: ${entry.missingPermissions.joinToString()}",
            )
        }
        if (entry.requiresNetwork && runtime.networkAvailable == false) {
            return fail(capabilityId, FailureCode.NETWORK_UNAVAILABLE, "network is required and unavailable")
        }
        if (!entry.currentlyAvailable || runtime.executionRestricted) {
            return fail(capabilityId, FailureCode.RUNTIME_UNAVAILABLE, "runtime cannot execute this capability now")
        }
        return NegotiationDecision(capabilityId, executable = true, reason = "intersection holds")
    }

    private fun alternativeFor(capabilityId: String, registry: LocalCapabilityRegistry): String? {
        val family = capabilityId.substringBefore('.')
        return registry.list().firstOrNull { other ->
            other.capabilityId != capabilityId &&
                other.capabilityId.startsWith("$family.") &&
                other.installed && other.enabled && other.authorized &&
                other.deviceSupported && other.currentlyAvailable && !other.revoked
        }?.capabilityId
    }

    private fun fail(
        capabilityId: String,
        code: FailureCode,
        reason: String,
        alternative: String? = null,
    ): NegotiationDecision {
        val recovery = if (alternative != null) {
            Recovery.SELECT_ALTERNATIVE
        } else {
            when (code) {
                FailureCode.CAPABILITY_NOT_INSTALLED -> Recovery.INSTALL
                FailureCode.CAPABILITY_DISABLED -> Recovery.ASK_USER
                FailureCode.AUTHORIZATION_REQUIRED, FailureCode.AUTHORIZATION_EXPIRED -> Recovery.REQUEST_PERMISSION
                FailureCode.PERMISSION_MISSING -> Recovery.REQUEST_PERMISSION
                FailureCode.NETWORK_UNAVAILABLE, FailureCode.RUNTIME_UNAVAILABLE -> Recovery.RETRY
                FailureCode.DEVICE_UNSUPPORTED, FailureCode.OS_UNSUPPORTED, FailureCode.TOOL_UNAVAILABLE ->
                    Recovery.SELECT_ALTERNATIVE
                else -> Recovery.REPORT_IMPOSSIBILITY
            }
        }
        return NegotiationDecision(
            capabilityId = capabilityId,
            executable = false,
            code = code,
            reason = reason,
            alternative = alternative,
            recovery = recovery,
        )
    }
}
