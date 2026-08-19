package me.spoo.internal

import io.ktor.client.engine.HttpClientEngine

/** The platform's default Ktor engine (OkHttp on Android and the JVM). */
internal expect fun defaultEngine(): HttpClientEngine

/** Cryptographically secure random bytes (SecureRandom on JVM targets). */
internal expect fun secureRandomBytes(count: Int): ByteArray

/** SHA-256 (MessageDigest on JVM targets; iOS actual arrives with that target). */
internal expect fun sha256(bytes: ByteArray): ByteArray
