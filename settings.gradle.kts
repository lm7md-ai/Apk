/*
 * Universal APK Factory & Studio — root Gradle settings.
 *
 * A universal mobile build system: ingests .zip source archives, existing
 * .apk files, local folders, Android Share Sheet payloads or GitHub
 * repository URLs, auto-detects the technology stack and packages the input
 * into a 4-byte aligned, V1/V2/V3 signed, installable .apk — entirely
 * on-device.
 *
 * Module map:
 *   :app           — The factory application (Kotlin + Jetpack Compose M3).
 *   :shell-webview — Zero-Gradle WebView shell APK (patched per project).
 *   :shell-python  — Python 3 host shell APK (patched per project).
 */
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // zipalign-java (com.github.iyxan23:zipalign-java) is published via JitPack.
        maven(url = "https://jitpack.io") {
            content { includeGroup("com.github.iyxan23") }
        }
    }
}

rootProject.name = "UniversalApkFactory"

include(":app")
include(":shell-webview")
include(":shell-python")
