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
│  core-detector    SmartProjectDetector (classification matrix) ·    │
│                   HybridConfigParser (apkconfig.json + fallbacks)  │
│  engine-patcher   FastApkPatcherAndEditor (ARSCLib)                 │
│  core-signer      KeyStoreGenerator (BouncyCastle PKCS12) ·         │
│                   ApkAlignerAndSigner (zipalign-java + apksig) ·    │
│                   ApkInstallReceiver (PackageInstaller)             │
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

### Android 10–15 compliance

* **Uncompressed `resources.arsc` / `.so`** — forced STORED and page-aligned (see pipeline step 5–6).
* **W^X (`noexec`) bypass** — bundled binaries are never `execve()`d from `filesDir`; the Python shell launches the interpreter via `/system/bin/linker64` (the Termux/AndroidIDE technique).
* **Keystore cryptography** — Android's crippled `BC` provider is replaced process-wide with the full `org.bouncycastle:bcpkix-jdk18on` provider (`PKCS12`).

## Building

Requires JDK 17 and the Android SDK (platform 35). Everything else is pulled by Gradle:

```bash
./gradlew :app:assembleDebug
```

The build automatically:
1. compiles `:shell-webview` and packages it as `app/src/main/assets/shells/webview_modern_shell.apk`,
2. compiles `:shell-python` and packages it as `app/src/main/assets/shells/python_runtime_shell.apk`,
3. builds the factory APK with both shells embedded.

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
shell-webview/            WebView Modern Shell (WebViewAssetLoader, JS bridge, file upload)
shell-python/             Python 3 host shell (linker64 exec, streaming console, stdin)
```

## License

Apache-2.0 — see [LICENSE](LICENSE). Bundled libraries are under their own licenses (ARSCLib: Apache-2.0, zipalign-java: MIT, BouncyCastle: MIT-like, OkHttp & AndroidX: Apache-2.0).
