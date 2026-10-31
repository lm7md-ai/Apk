# Universal APK Factory & Studio

**A universal mobile build system and APK studio for Android.** 100% Kotlin + Jetpack Compose (Material 3), `arm64-v8a`, `minSdk 26`, `targetSdk 35`.

Turn any of these inputs into a signed, installable `.apk` — entirely on-device:

| Input | Channel |
| --- | --- |
| `.zip` source archive | SAF file picker, Share Sheet, `ACTION_VIEW` |
| Existing `.apk` | SAF file picker, Share Sheet (`MODE_APK_EDITOR`: modify / clone / re-sign) |
| Project folder | SAF folder picker (recursive copy into the workspace) |
| GitHub repository URL | Built-in downloader (OkHttp + `codeload.github.com`) |
| GitHub repo link shared as text | Android Share Sheet (`ACTION_SEND`, `text/plain`) |

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│ :app  — Universal APK Factory (Kotlin, Jetpack Compose Material 3) │
│                                                                    │
│  core-ingestion   IngestionManager · ZipRootNormalizer ·            │
│                   GitHubRepoDownloader                             │
│  core-diag        BuildFailureAnalyzer (failure classification +     │
│                   ready-to-send AI support prompt)                  │
│  core-python      PythonRuntimeManager (CPython aarch64 runtime     │
│                   provisioning + injection into MODE_FAST_PYTHON)   │
│  core-detector    SmartProjectDetector (classification matrix) ·    │
│                   HybridConfigParser (apkconfig.json + fallbacks)  │
│  engine-patcher   FastApkPatcherAndEditor (ARSCLib)                 │
│  core-signer      KeyStoreGenerator (BouncyCastle PKCS12) ·         │
│                   ApkAlignerAndSigner (zipalign-java + apksig) ·    │
│                   ApkInstallReceiver (PackageInstaller)             │
│  engine-gradle    ToolchainManager (JDK/Gradle/SDK provisioning) ·   │
│                   NativeGradleService (on-device ARM64 builds)       │
│  engine-cloud     GitHubCloudBuilder (ephemeral Actions runner repo) │
│  pipeline         BuildOrchestrator (engine routing)                 │
│  ui               Compose Material 3 dashboard, config sheet,        │
│                   live terminal, keystore manager                    │
└────────────────────────────────────────────────────────────────────┘
┌────────────────────────────────────────────────────────────────────┐
│ :shell-webview — compiled at build time into                        │
│                  app assets: shells/webview_modern_shell.apk        │
│                  (WebViewAssetLoader · https://appassets.androidplatform.net) │
├────────────────────────────────────────────────────────────────────┤
│ :shell-python  — compiled at build time into                        │
│                  app assets: shells/python_runtime_shell.apk        │
│                  (CPython host shell, /system/bin/linker64 exec)    │
└────────────────────────────────────────────────────────────────────┘
```

### Stack classification matrix (Module 2)

| Detection | Mode |
| --- | --- |
| `AndroidManifest.xml` (binary) + `classes.dex` | `MODE_APK_EDITOR` |
| `package.json` with react/vue/vite/next/capacitor, no prebuilt `dist/`/`build/` | `MODE_CLOUD_WEB_OR_HYBRID` |
| `index.html` (or a prebuilt `dist/`/`build/` bundle) | `MODE_FAST_WEB` |
| `main.py`, `flet.json`, `requirements.txt` | `MODE_FAST_PYTHON` |
| `pubspec.yaml` | `MODE_CLOUD_FLUTTER` |
| `build.gradle` / `build.gradle.kts` | `MODE_NATIVE_GRADLE` |

Every classification produces a human-readable **evidence trail** shown in the UI.

### The zero-Gradle instant patch pipeline (Module 3 → 4)

1. `ApkModule.loadApkFile()` loads `webview_modern_shell.apk`, `python_runtime_shell.apk`, or the user's APK.
2. `AndroidManifestBlock` mutation: `packageName` (+ every `<provider android:authorities>`), `versionCode`/`versionName`, `android:label`, `<uses-permission>` add/remove, `android:screenOrientation`.
3. Launcher icon replacement across `res/mipmap-*` on the full DPI grid (48–192 px legacy, 108–432 px adaptive layers, average-color background).
4. Stream-injection of the normalized payload under `assets/www/` / `assets/python_app/` + `assets/shell_config.json`.
5. Storage rules: `resources.arsc` + `lib/**/*.so` forced to **STORED** (registered in `UncompressedFiles`, the authoritative list ARSCLib re-applies on write — API 30+ hard requirement).
6. `ZipAlign.alignZip()` → 4-byte alignment (16 KiB for `.so`).
7. `ApkSigner` → **V1 + V2 + V3** signatures with the per-package persistent keystore.
8. `PackageInstaller` session commit (+ `FileProvider`/`ACTION_VIEW` fallback).

### The cloud CI bridge (Module 6 — engine-cloud-bridge)

Node/Flutter/Gradle projects are compiled on GitHub's free `ubuntu-latest` runners using the user's Personal Access Token:

1. The PAT is stored in `EncryptedSharedPreferences` (hardware-backed AES-256-GCM master key). Create a **classic token** at github.com/settings/tokens with the `repo`, `workflow` and `delete_repo` scopes.
2. An **ephemeral private repo** `universal-apk-factory-runner` is created and the normalized project is pushed through the Git Data API (base64 blobs → chunked trees → commit → `refs/heads/main`) together with a generated `.github/workflows/universal_build.yml`.
3. Four workflow recipes are generated (no Actions template expressions — the YAML lives verbatim in Kotlin raw strings): **WEB_NODE** (npm ci/install + npm run build), **CAPACITOR** (cap add/sync + Gradle assembleDebug), **FLUTTER** (flutter build apk --release), **GRADLE** (gradlew/gradle assembleDebug).
4. The run is polled every 5 s; job steps map to UI progress ("3/7 Build web bundle").
5. The artifact zip is downloaded and extracted; web bundles are wrapped into the WebView shell locally, APKs are sanitized (stale JAR signatures stripped), 4-byte aligned and V1/V2/V3-signed locally.
6. The ephemeral repo is deleted (kept after a failure so the run log stays reachable; the next build cleans it up).

Upload caps: 8 000 files, 20 MiB per file, 150 MiB total. The runner waits up to 30 minutes for completion.

### The on-device ARM64 compiler (Module 5 — engine-native-gradle)

Gradle projects can also be compiled **entirely on the phone** (ARM64 only). `ToolchainManager` provisions, verifies and caches three components under `filesDir/toolchain/` on first use:

| Component | Source | Verification |
|-----------|--------|--------------|
| OpenJDK 21 (Temurin aarch64) `jdk-21.0.9+10` | Adoptium GitHub release (`.tar.gz`) | published `.sha256.txt` sibling |
| Gradle 8.9 `-bin.zip` | services.gradle.org | published `.sha256` sibling |
| Android SDK (AndroidIDE aarch64 build) | AndroidIDEOfficial/androidide-tools release (`.tar.xz`) | structural (tar headers + XZ stream), no checksum published upstream |

`NativeGradleService` (special-use foreground service) then runs:

```
/system/bin/linker64 $JAVA_HOME/bin/java \
    -classpath $GRADLE_HOME/lib/gradle-launcher-<ver>.jar \
    org.gradle.launcher.daemon.bootstrap.GradleDaemon \
    --no-daemon --console=plain --stacktrace \
    -Pandroid.aapt2FromMavenOverride=<nativeLibraryDir>/libaapt2.so \
    assembleDebug
```

with `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME`, `TMPDIR`, `HOME` and `LD_LIBRARY_PATH` sandboxed inside the factory's storage. stdout/stderr stream into the Live Terminal Log Viewer through a `MutableSharedFlow`; on success the produced APKs are sanitized, aligned and signed locally. If exec from app storage is blocked on a given ROM, the terminal reports it and the cloud engine remains available.

### Android 10–15 compliance

* **Uncompressed `resources.arsc` / `.so`** — forced STORED and page-aligned (see pipeline step 5–6).
* **W^X (`noexec`) bypass** — bundled binaries are never `execve()`d from `filesDir`; the Python shell launches the interpreter via `/system/bin/linker64` (the Termux/AndroidIDE technique), and the on-device Gradle service does the same for the JVM launcher.
* **aapt2 as `jniLibs/arm64-v8a/libaapt2.so`** — AGP spawns aapt2 via `execve()`, which Android 10+ blocks in app storage for targetSdk 29+. The factory therefore packages the AndroidIDE build-tools 34.0.4 aarch64 aapt2 inside its own APK at factory build time (`downloadAapt2Runtime` task); with `useLegacyPackaging=true` Android extracts it to `nativeLibraryDir`, which is executable. It is passed to Gradle via `-Pandroid.aapt2FromMavenOverride`.
* **Android 14 special-use foreground service** — `NativeGradleService` declares `foregroundServiceType="specialUse"` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property, as required by API 34+.
* **Keystore cryptography** — Android's crippled `BC` provider is replaced process-wide with the full `org.bouncycastle:bcpkix-jdk18on` provider (`PKCS12`).

## Design system — "Amber Forge" v2

The whole UI was redesigned around a coherent in-house design system:

* **Palette** — industrial amber on deep graphite (dark, "the factory
  floor") and warm paper (light), with extended semantic tokens
  (success / warning / info) beyond Material 3's slots. Brand colors are
  used consistently instead of dynamic theming.
* **UiKit** — shared visual vocabulary (`ui/components/UiKit.kt`): icon
  badges, card headers, status pills, info banners, evidence boxes and the
  signature amber **GradientButton** for the single primary action of each
  screen.
* **Motion** — animated screen transitions and a pulsing build phase
  indicator.
* **Screens** — hero brand header + readiness strip on Home, icon-badged
  section cards on Configure, a gradient success band + copyable log card
  on Build, and status pills on Keystores & Cloud.

## Localization (English + العربية)

The full UI is localized (150 strings) with a **language card on the home
screen** (System / English / العربية) that applies instantly and persists.
Arabic gets complete RTL mirroring (`supportsRtl` + locale-wrapped base
context), and Android 13+ exposes the app in the system per-app language
settings (`localeConfig`). Placeholders and token formats stay verbatim.

## Smart failure diagnosis & AI support prompt

Every failed build is analyzed by `core-diag/BuildFailureAnalyzer`:

* The error message and the terminal-log tail are matched against failure
  signatures (GitHub auth, connectivity, disk space, toolchain, cloud CI
  runs, detection, corrupted payloads, signing, icons, OOM, Gradle
  compilation errors …).
* The Build screen shows a **diagnosis card**: category, probable cause and
  ordered recovery steps (plus a direct link to the failed Actions run).
* The card renders a **ready-to-send AI prompt** (Arabic framing, verbatim
  English log excerpts, full build context) with one-tap *Copy* / *Share* —
  paste it into any AI assistant for a deeper fix.

## Self-healing builds — auto-repair (v6)

The factory never gives up on the first error. `BuildAutoRepairEngine`
(`core/diag`) runs in two places:

1. **Pre-flight (before the first attempt)** — silently fixes what is
   fixable and logs every change: an invalid Android package id is
   rewritten to a valid one, broken version fields are reset, a missing
   Python entry is re-detected (`main.py` → best `.py` candidate), a
   missing web entry falls back to the best `.html` candidate.
2. **Failure-driven (between automatic retries)** — the failed attempt is
   matched against repair rules, the inputs are repaired and the build
   **retries automatically (up to 3 attempts)**: an icon that cannot be
   rendered is dropped in favor of the default, an APK nested inside a
   zip is extracted and used directly, transient network errors are
   retried after a 5 s backoff.

Everything the engine fixed is shown live on the Build screen in an amber
**Auto-repair** card, written to the terminal log, and — when the retries
are exhausted — embedded as a dedicated section of the AI prompt ("what
the app already tried — do not suggest it again") so any assistant goes
straight to the real root cause.

## Python runtime — bundled since v5

**The factory APK ships with a complete CPython aarch64-android runtime.**
`MODE_FAST_PYTHON` works out of the box: zero downloads, zero setup, and
Python projects turn into installable APKs in seconds (pure patching — no
compilation). On top of that:

* **`requirements.txt` support** — generated apps pip-install the project's
  pure-Python packages from PyPI into `site-packages/` on first launch
  (internet required once; compiled extension wheels are reported and
  skipped — the app still runs).
* **Custom runtime override** — the configuration screen's **Python runtime**
  card can still download any CPython aarch64-android distribution
  (tar.gz / zip, AndroidIDE or Termux style) with SHA-256 verification; a
  custom runtime fully replaces the bundled tree in subsequent builds
  (`removeDir` + re-injection at patch time).

### How the bundle is built (CI)

CI (`.github/workflows/build-apk.yml`) provisions the runtime into
`:shell-python` before every Gradle build:

1. downloads the pinned CPython-for-Android release
   ([Sportacandy/cpython-android-prebuilt](https://github.com/Sportacandy/cpython-android-prebuilt),
   built with CPython's official `Platforms/Android` support — PSF license),
   verifying its SHA-256;
2. copies the `lib/` tree (libpython + stdlib + OpenSSL/SQLite), stripping
   the test suite and GUI-only modules;
3. extracts the official **pip wheel** (pinned, SHA-256 verified,
   self-contained with vendored dependencies + CA bundle) into the runtime's
   `site-packages/` — the CPython Android package ships neither pip nor
   ensurepip wheels;
4. compiles `shell-python/launcher/python_launcher.c` — a 3-line
   `Py_BytesMain` launcher, the exact entry point of the real interpreter —
   with the Android NDK for aarch64 (16 KiB page-aligned) into
   `bin/python3.x`, because CPython's Android packaging is embedding-only
   and ships no executable;
5. bundles the tree as `assets/python_runtime/` of the python shell
   template. The factory reports it via `PythonRuntimeManager.bundledRuntimeInfo()`
   (probe cached per app version).

At app first launch the shell extracts the tree once, resolves packaged
text-symlinks, and runs the interpreter through `/system/bin/linker64`
(Termux/AndroidIDE W^X bypass).

## Building

Requires JDK 17 and the Android SDK (platform 35). Everything else is pulled by Gradle:

```bash
./gradlew :app:assembleDebug
```

The build automatically:
1. compiles `:shell-webview` and packages it as `app/src/main/assets/shells/webview_modern_shell.apk`,
2. compiles `:shell-python` and packages it as `app/src/main/assets/shells/python_runtime_shell.apk`,
3. downloads the AndroidIDE aarch64 aapt2 and packages it as `app/src/main/jniLibs/arm64-v8a/libaapt2.so` (override the URL with `-PapkfactoryAapt2Url=<tar.xz>`; skipped when the file already exists),
4. builds the factory APK with both shells and the aapt2 runtime embedded.

### Deterministic debug signing (v6.1)

The debug APK is signed with the shared CI identity committed at
`keystores/factory-debug.keystore` (a public debug key by convention —
store/key password `android`, alias `apkfactory` — the same practice as
AOSP's well-known debug key). Without it, every machine signs with its own
`~/.android/debug.keystore` and GitHub **rotates** that key between runner
image updates, so consecutive releases end up with different certificates —
and Android then refuses to update one over the other ("App not installed").
With the shared identity every build (local or CI) signs identically, and
each release installs cleanly over the previous one.

### Provisioning a custom Python runtime (optional override)

Locally (or to override the CI bundle), bundle any CPython 3 aarch64-android
distribution (`bin/python3.1x` + `lib/libpython3.1x.so` layout) from a source
you trust:

```bash
./gradlew :shell-python:downloadPythonRuntime \
    -PpythonRuntimeUrl="https://<host>/cpython-3.12-aarch64-android.tar.gz" \
    -PpythonRuntimeSha256="<sha-256 of the archive>"
```

Without a runtime the shell still builds and runs — it reports the missing runtime on screen.

## Project layout

```
app/                      The factory application
  src/main/java/com/universal/apkfactory/
    core/model/           ProjectModels (IngestedSource, BuildMode, ProjectBuildConfig, PermissionCatalog)
    core/ingestion/       IngestionManager, ZipRootNormalizer, GitHubRepoDownloader
    core/detector/        SmartProjectDetector, HybridConfigParser
    engine/patcher/       FastApkPatcherAndEditor (ARSCLib pipeline)
    core/signer/          CryptoProviders, KeyStoreGenerator, ApkAlignerAndSigner, ApkInstallReceiver
    engine/gradle/        ToolchainManager, TarArchiveReader, NativeGradleService
    engine/cloud/         GitHubCloudBuilder (REST v3 + workflow YAML generators)
    pipeline/             BuildOrchestrator (fast / cloud / native routing)
    di/                   ServiceLocator
    ui/                   theme, screens (Home/Config/Build/Keystores), TerminalLogViewer
  MainViewModel.kt        State machine: ingest → detect → configure → build → install
  MainActivity.kt         singleTask activity, Share Sheet / ACTION_VIEW handler
shell-webview/            WebView Modern Shell (WebViewAssetLoader, JS bridge, file upload)
shell-python/             Python 3 host shell (linker64 exec, streaming console, stdin)
```

## License

Apache-2.0 — see [LICENSE](LICENSE). Bundled libraries are under their own licenses (ARSCLib: Apache-2.0, zipalign-java: MIT, BouncyCastle: MIT-like, OkHttp & AndroidX: Apache-2.0).
