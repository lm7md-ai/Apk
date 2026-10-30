# Roadmap — Universal APK Factory & Studio

Ideas ordered by impact; anything unchecked is open for contribution.

## Build paths

- [x] Smart failure diagnosis + ready-to-send AI support prompt
- [x] Python runtime provisioning (download, SHA-256 verify, inject)
- [x] HTML entry fallback (any `.html` when no `index.html` exists)
- [x] Disk-space pre-flight before on-device Gradle builds
- [ ] **APK Editor deep mode** — decompile resources (ARSCLib manifest
      rewriting exists), edit permissions/activity list in a forms UI,
      swap icons inside existing APKs, multi-APK batch re-signing.
- [ ] **Cloud builds without a PAT** — optional embedded runner-service
      account or a "bring your own Actions repo" template flow that guides
      the user through creating a dedicated repo once.
- [ ] **Flutter on device** — bundle the Flutter ARM64 toolchain the same
      way the Gradle toolchain is provisioned (large, but the plumbing
      exists).
- [ ] **Node on device** — ship a Node.js aarch64 runtime so
      `MODE_CLOUD_WEB_OR_HYBRID` can `npm ci && npm run build` locally.
- [ ] **Build queue** — multiple projects, sequential builds, history of
      generated APKs with re-download/re-sign.

## Runtime / shells

- [ ] **Shell extensions store** — camera, geolocation, notifications,
      file-system bridges injected as opt-in permissions in `shell_config.json`.
- [x] **Python pip support** — the Python shell pip-installs the project's
      `requirements.txt` (pure-Python packages) into `site-packages/` on the
      app's first launch; a digest marker avoids re-installs. *(v5 — together
      with the CPython runtime bundled into the factory APK, which removed
      the manual provisioning step entirely.)*
- [ ] **Live reload** — ADB/Wi-Fi shell reload for fast iteration while
      developing the web payload.
- [ ] **V8/QuickJS shell** — a JavaScript-native shell for JS-only apps.

## Platform & UX

- [ ] **AI-assisted auto-repair** — on failure, send the generated prompt to
      a user-configured AI endpoint and offer a patch automatically.
- [ ] **App library screen** — installed factory-built apps, launch/uninstall,
      version history, diff of configs.
- [ ] **Profiles** — per-project keystore + icon + permission presets.
- [ ] **Incremental builds** — cache the patched shell between runs and only
      swap the payload + manifest deltas.
- [ ] **AAB output** — Android App Bundles for Play distribution.
- [ ] **Wear/TV/desktop targets** — shell variants for other form factors.
- [x] **Multi-language UI** — Arabic-first localization (strings.xml
      `values-ar`), RTL mirroring, in-app language switcher.
- [ ] **Telemetry (opt-in)** — anonymous failure-category counters to
      prioritize the most common build failures.

## Hardening

- [ ] **VirusTotal / Play Protect pre-scan** of generated APKs before
      install (opt-in, privacy-conscious).
- [ ] **Reproducible builds** — pin + verify every toolchain artifact by
      digest manifest.
- [ ] **Fuzz the ingestion layer** — ZipRootNormalizer / TarArchiveReader /
      PythonRuntimeManager against malformed archives.
