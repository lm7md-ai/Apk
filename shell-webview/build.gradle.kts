/*
 * WebView Modern Shell — application module.
 *
 * Compiles the ultra-lightweight WebView shell APK that the factory loads
 * through ARSCLib at patch time (engine-fast-patcher). The shell is
 * dependency-minimal on purpose: every class in its classes.dex costs every
 * produced app its size.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.universal.apkfactory.shell.webview"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.universal.apkfactory.shell.webview"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // WebViewAssetLoader — serves assets/www/ over https://appassets.androidplatform.net
    implementation(libs.androidx.webkit)
}

/*
 * =====================================================================================
 * SHELL PACKAGING TASK
 * =====================================================================================
 *
 * Assembles this module and copies the (unsigned) release APK into the
 * factory's assets as `webview_modern_shell.apk`. Unsigned is intentional:
 * the patched output is stripped of any old signatures and re-signed by the
 * core-signer engine, so the template never needs a signing identity.
 *
 * :app's preBuild task depends on this task (see app/build.gradle.kts), so
 * the asset is always fresh before the factory's asset merge runs.
 */
tasks.register<Copy>("packageWebviewShell") {
    group = "apkfactory"
    description = "Assembles :shell-webview and packages it as app assets/shells/webview_modern_shell.apk."
    dependsOn("assembleRelease")

    // The shell has no product flavors and no splits, so the release output
    // directory contains exactly one APK ("shell-webview-release-unsigned.apk").
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(rootProject.file("app/src/main/assets/shells"))
    rename(org.gradle.api.Transformer<String, String> { _ -> "webview_modern_shell.apk" })
}
