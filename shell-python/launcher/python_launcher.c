/*
 * Minimal CPython launcher — Universal APK Factory & Studio
 * Copyright (C) 2026 Universal APK Factory contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * =====================================================================================
 *
 * Why this file exists
 * --------------------
 * CPython's official Android packaging (`Platforms/Android` in the CPython
 * source tree) distributes a prefix that is deliberately aimed at *embedding*
 * libpython via JNI: it ships `lib/libpython3.x.so` + the standard library +
 * headers, but NO `bin/python3.x` executable. The factory's Python host
 * shell instead runs the interpreter as a process (through
 * /system/bin/linker64, the Android 10+ W^X bypass), so it needs an ELF
 * executable under `bin/`.
 *
 * This launcher is byte-for-byte the equivalent of CPython's own
 * `Programs/python.c`: it forwards to Py_BytesMain, the exact entry point the
 * real interpreter executable uses. Cross-compiled once with the Android NDK
 * (aarch64) by the CI provisioning step and placed at
 * `assets/python_runtime/bin/python3.x`, it turns the embedding-only
 * distribution into a fully runnable interpreter.
 *
 * Build (as performed by .github/workflows/build-apk.yml):
 *
 *   clang --target=aarch64-linux-android26 \
 *         -I<prefix>/include/python3.x \
 *         -o bin/python3.x python_launcher.c \
 *         -L<prefix>/lib -lpython3.x \
 *         -Os -fPIE -pie -Wl,-O1 -Wl,-z,max-page-size=16384
 *
 * libpython3.x.so is resolved at runtime through LD_LIBRARY_PATH (set by the
 * shell before launch), the same mechanism Termux uses.
 */
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
