# VDX user model

VDX owns the local user model. It is not uploaded as a complete memory on the hot path.

`UserMemory` remains the fact store (contact, habit, default). It is not replaced.

`UserPreference` is the preference record:

- preference id, user id, domain, subject, value
- source, confidence, scope
- created, updated, last observed

Sources: explicit user statement, repeated behavior, single behavior, imported profile, application signal, system inference.

An inference is not an explicit statement. `PreferenceBook` refuses to overwrite an explicit preference with a non-explicit source. A repeated choice may be stored at confidence 0.91 with source `REPEATED_BEHAVIOR`. That record stays an inference.

`IntentProjectionBuilder` sends only preferences whose domain matches the request. Other preferences stay on the device.
