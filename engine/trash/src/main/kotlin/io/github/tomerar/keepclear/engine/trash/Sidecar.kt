// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.engine.trash

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The self-describing file written next to each trashed item (design.md §7, SEC-1, MIG-2).
 * Treated as **untrusted input**: strict parsing, size cap, versioned, HMAC over the canonical JSON.
 */
@Serializable
data class Sidecar(
    val v: Int = VERSION,
    val id: String,
    val originalPath: String,
    val trashedName: String,
    val size: Long,
    val mtimeMillis: Long,
    val sha256: String? = null,
    val ruleId: String? = null,
    val reason: String,
    val deletedAt: Long,
    val expiresAt: Long,
    val verifiedCopy: String? = null,
) {
    companion object {
        const val VERSION = 1
        const val MAX_BYTES = 64 * 1024

        private val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = true
            encodeDefaults = true
        }

        /** Only for sidecars a newer version wrote: their extra fields are expected (E-MIG-4). */
        private val newerJson = Json(json) { ignoreUnknownKeys = true }

        @Serializable
        private data class Signed(val body: Sidecar, val mac: String)

        @Serializable
        private data class VersionOnly(val v: Int)

        @Serializable
        private data class SignedVersion(val body: VersionOnly)

        fun encode(sidecar: Sidecar, signer: SidecarSigner): ByteArray {
            val canonical = json.encodeToString(serializer(), sidecar).toByteArray(Charsets.UTF_8)
            return json.encodeToString(
                Signed.serializer(),
                Signed(sidecar, signer.sign(canonical)),
            ).toByteArray(Charsets.UTF_8)
        }

        sealed interface Decoded {
            data class Ok(val sidecar: Sidecar, val trusted: Boolean) : Decoded

            data class Rejected(val reason: String) : Decoded
        }

        /** Never throws on hostile input (E-SEC-3). */
        fun decode(bytes: ByteArray, signer: SidecarSigner): Decoded {
            if (bytes.size > MAX_BYTES) return Decoded.Rejected("too large")
            val text = bytes.toString(Charsets.UTF_8)
            val version = try {
                newerJson.decodeFromString(SignedVersion.serializer(), text).body.v
            } catch (e: Exception) {
                return Decoded.Rejected("malformed: ${e.javaClass.simpleName}")
            }
            // A newer app may add fields: read what we know, never trust it, but surface it instead of leaving an
            // orphan file in the trash. Our own version stays strict (unknown keys = tampering).
            val parser = if (version > VERSION) newerJson else json
            val signed = try {
                parser.decodeFromString(Signed.serializer(), text)
            } catch (e: Exception) {
                return Decoded.Rejected("malformed: ${e.javaClass.simpleName}")
            }
            if (signed.body.v > VERSION) return Decoded.Ok(signed.body, trusted = false) // newer app wrote it (E-MIG-4)
            val canonical = json.encodeToString(serializer(), signed.body).toByteArray(Charsets.UTF_8)
            return Decoded.Ok(signed.body, trusted = signer.verify(canonical, signed.mac))
        }
    }
}
