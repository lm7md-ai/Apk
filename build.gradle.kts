/*
 * Universal APK Factory & Studio — root build script.
 *
 * Plugin versions are centrally managed in gradle/libs.versions.toml so the
 * application module, the WebView shell module and the Python shell module
 * all compile against one consistent toolchain (Kotlin 2.0, AGP 8.7).
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
