/*
 * Python Runtime Shell — application module.
 *
 * Compiles the Python 3 host shell APK that the factory loads through
 * ARSCLib at patch time. The CPython aarch64-android distribution itself is
 * provisioned on demand into assets/python_runtime/ by the
 * `downloadPythonRuntime` task below.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.universal.apkfactory.shell.python"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.universal.apkfactory.shell.python"
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

    sourceSets {
        getByName("main") {
            // assets/python_runtime is populated by the downloadPythonRuntime
            // task; the directory may legitimately be absent (the shell then
            // reports the missing runtime on screen).
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    // Intentionally empty: pure framework (Activity + Views + org.json).
}

/*
 * =====================================================================================
 * PYTHON RUNTIME PROVISIONING
 * =====================================================================================
 *
 * Bundles a CPython 3 aarch64-android distribution into
 * shell-python/src/main/assets/python_runtime/. Pass any distribution that
 * ships an ELF interpreter layout (bin/python3.x + lib/libpython3.x.so).
 *
 *   ./gradlew :shell-python:downloadPythonRuntime \
 *       -PpythonRuntimeUrl="https://example.org/cpython-3.12-aarch64-android.tar.gz" \
 *       [-PpythonRuntimeSha256="<sha-256 of the archive>"]
 *
 * Both .zip and .tar.gz archives are supported. A single redundant top-level
 * directory inside the archive is stripped automatically (same rule as the
 * factory's ZipRootNormalizer). The SHA-256, when provided, is verified
 * before extraction.
 */
// -PpythonRuntimeUrl / -PpythonRuntimeSha256 (both optional; the URL is
// mandatory for the task to run, the hash enables verification).
fun pythonRuntimeUrlProperty(): String? =
    (project.findProperty("pythonRuntimeUrl") as String?)?.takeIf { it.isNotBlank() }

fun pythonRuntimeSha256Property(): String? =
    (project.findProperty("pythonRuntimeSha256") as String?)?.takeIf { it.isNotBlank() }

tasks.register("downloadPythonRuntime") {
    group = "apkfactory"
    description =
        "Downloads + verifies a CPython aarch64-android distribution into :shell-python assets."

    doLast {
        val url = pythonRuntimeUrlProperty()
            ?: throw GradleException(
                """
                |
                |downloadPythonRuntime: -PpythonRuntimeUrl=<archive url> is required.
                |
                |Provide a CPython 3 build for Android/aarch64 with the layout
                |  bin/python3.1x        (ELF, arm64)
                |  lib/libpython3.1x.so
                |and (optionally) its SHA-256 via -PpythonRuntimeSha256.
                |
                |Suggested sources: a CPython-for-Android release build (for
                |example the distributions published by the Termux package
                |project or androidide-tools), or your own NDK cross-build.
                """.trimMargin()
            )

        val cacheDir = layout.buildDirectory.dir("python-runtime-download").get().asFile
        cacheDir.mkdirs()
        val archiveFile = File(cacheDir, "runtime-archive")

        logger.lifecycle("Downloading Python runtime from $url …")
        val connection = java.net.URI(url).toURL().openConnection() as java.net.URLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", "UniversalApkFactory/2.0")
        connection.getInputStream().use { input ->
            archiveFile.outputStream().use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }

        val expectedSha256 = pythonRuntimeSha256Property()
        if (expectedSha256 != null) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            archiveFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { byte ->
                "%02x".format(byte)
            }
            val expected = expectedSha256.trim().lowercase()
            if (actual != expected) {
                archiveFile.delete()
                throw GradleException(
                    "SHA-256 mismatch for the Python runtime archive.\n" +
                        "expected: $expected\nactual:   $actual"
                )
            }
            logger.lifecycle("SHA-256 verified: $actual")
        } else {
            logger.warn("No -PpythonRuntimeSha256 given — archive NOT verified.")
        }

        val targetDir = File(projectDir, "src/main/assets/python_runtime")
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        val isZip = url.substringBefore('?').endsWith(".zip")
        val tree = if (isZip) {
            project.zipTree(archiveFile)
        } else {
            project.tarTree(project.resources.gzip(archiveFile))
        }

        project.copy {
            from(tree)
            into(targetDir)
            // Strip a single redundant top-level directory, mirroring the
            // ZipRootNormalizer rule of the factory itself.
            eachFile { file ->
                val segments = file.relativePath.segments
                if (segments.size > 1 && segments[0].let { top ->
                        listOf(
                            "app", "src", "gradle", "build", "dist", "out",
                            "assets", "res", "www", "public", "lib", "bin"
                        ).none { it == top.lowercase() }
                    }
                ) {
                    file.relativePath = org.gradle.api.file.RelativePath(
                        true,
                        *segments.drop(1).toTypedArray()
                    )
                }
            }
            includeEmptyDirs = false
        }

        // Persist the interpreter layout the shell expects.
        val hasInterpreter = File(targetDir, "bin").isDirectory &&
            (File(targetDir, "bin").listFiles()
                ?.any { it.name.startsWith("python") } == true)
        if (!hasInterpreter) {
            val found = targetDir.walkTopDown()
                .filter { it.isFile }
                .take(12)
                .joinToString("\n  ") { it.relativeTo(targetDir).path }
            throw GradleException(
                "The downloaded archive does not contain bin/python* .\n" +
                    "First entries:\n  $found"
            )
        }
        logger.lifecycle("Python runtime provisioned into ${targetDir.relativeTo(rootDir)}")
    }
}

/*
 * Assembles this module and copies the (unsigned) release APK into the
 * factory's assets as `python_runtime_shell.apk`. :app's preBuild task
 * depends on this task (see app/build.gradle.kts).
 */
tasks.register<Copy>("packagePythonShell") {
    group = "apkfactory"
    description =
        "Assembles :shell-python and packages it as app assets/shells/python_runtime_shell.apk."
    dependsOn("assembleRelease")

    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(rootProject.file("app/src/main/assets/shells"))
    rename { _ -> "python_runtime_shell.apk" }
}
