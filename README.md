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

## Python runtime provisioning

`MODE_FAST_PYTHON` is fully enabled at runtime: the configuration screen
offers a **Python runtime** card that downloads a CPython aarch64-android
distribution (tar.gz / zip, AndroidIDE or Termux style) with SHA-256
verification, extracts it (preserving symlinks through the packaged
text-symlink convention the shell resolves at first launch), and injects it
under `assets/python_runtime/` of every generated app. Without a runtime the
build still succeeds — the generated app shows a setup notice.

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

### Provisioning the Python runtime (optional, for MODE_FAST_PYTHON)

The Python shell needs a CPython 3 aarch64-android distribution (`bin/python3.1x` + `lib/libpython3.1x.so` layout). Bundle one from any source you trust:

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
