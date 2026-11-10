# Capability lifecycle on VDX

The local registry is a projection. It is not a second App Store.

An entry has capability id, application id, version, enabled, authorized, device supported, currently available, permissions, execution target, schema ref, and source.

Lifecycle this plane applies locally, from a manifest it was given:

- install: upsert the projection
- enable / disable: flip `enabled`
- revoke: `applyRevocation` clears authorization and availability
- uninstall: remove the entry

Negotiation runs before execution when the capability is projected. Order: exists, installed, enabled, authorized, supported, permissions, network, runtime. A miss returns a specific failure code and, when one exists, an alternative in the same family.

A capability that was never projected does not block the existing voice path. A projected capability that fails the intersection does not reach execution.

VDX does not query the App Store on that check. After the projection is local, the hot path is VDX to Elastic Web.
