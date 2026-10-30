/*
 * Universal APK Factory & Studio
 * Copyright (C) 2026 Universal APK Factory contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.universal.apkfactory.core.signer

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Process-wide crypto provider registry.
 *
 * Android ships a **crippled** built-in `BC` provider (it cannot create
 * PKCS12 keystores with several modern algorithms and throws
 * `KeyStoreException` / `NoSuchAlgorithmException`). This object removes the
 * platform provider and installs the complete
 * `org.bouncycastle:bcpkix-jdk18on` implementation exactly once per process.
 *
 * Every engine in core-signer references [BOUNCY_CASTLE] explicitly, so
 * provider ordering can never affect correctness.
 */
object CryptoProviders {

    /** The single, complete BouncyCastle provider used by all engines. */
    val BOUNCY_CASTLE: BouncyCastleProvider = BouncyCastleProvider().also { provider ->
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(provider, 1)
    }

    /** Idempotent installation hook used by the Application class. */
    fun ensureInstalled() {
        // Touching BOUNCY_CASTLE performs the installation exactly once.
        check(BOUNCY_CASTLE.name == BouncyCastleProvider.PROVIDER_NAME)
    }
}
