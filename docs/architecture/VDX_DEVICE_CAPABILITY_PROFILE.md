# VDX device capability profile

VDX owns this profile. Elastic Web does not. The App Store does not.

`DeviceCapabilityDiscovery.from` copies a probe snapshot. A field the probe did not return stays null. The discovery engine does not treat "Android" as camera, microphone, GPS, or network.

`AndroidDeviceProbe` reads OS, API level, manufacturer, model, ABI, RAM, and data-partition storage when those calls succeed. GPU is always null on this path. There is no honest GPU query here, so none is invented. Installed applications are launcher-visible packages only. A failed read is null, not a guessed false.

The profile is versioned (`profileVersion = 1`) and carries `generatedAt`. `ref()` changes when platform version or API level changes, so two OS versions are not the same profile.

Runtime state (network, battery) is observed. It is not assumed from the platform name.
