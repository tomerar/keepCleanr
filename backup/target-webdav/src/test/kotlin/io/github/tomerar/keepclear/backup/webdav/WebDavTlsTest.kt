// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.webdav

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * M17: HTTPS is checked like a browser does (trusted chain + matching name), never "trust all". Certificates are
 * made with the JDK's keytool for each test.
 */
class WebDavTlsTest {
    @TempDir
    lateinit var dir: File

    private fun keystore(name: String, san: String): KeyStore {
        val file = File(dir, "$name.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val exit = ProcessBuilder(
            keytool,
            "-genkeypair",
            "-alias",
            "k",
            "-keyalg",
            "EC",
            "-groupname",
            "secp256r1",
            "-dname",
            "CN=$name",
            "-ext",
            "SAN=$san",
            "-validity",
            "2",
            "-storetype",
            "PKCS12",
            "-keystore",
            file.path,
            "-storepass",
            "changeit",
            "-keypass",
            "changeit",
        ).redirectErrorStream(true).start().also { it.inputStream.readAllBytes() }.waitFor()
        exit shouldBe 0
        return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "changeit".toCharArray()) } }
    }

    private fun serverContext(ks: KeyStore) = SSLContext.getInstance("TLS").apply {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "changeit".toCharArray())
        init(kmf.keyManagers, null, null)
    }

    /** A client that trusts exactly this certificate (what a pinned self-signed certificate would do). */
    private fun trusting(ks: KeyStore): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(ks)
            trustManagers.filterIsInstance<X509TrustManager>().single()
        }

    @Test
    fun `an untrusted certificate is rejected`(): Unit = runBlocking {
        val ks = keystore("self", "ip:127.0.0.1")
        FakeDavServer(tls = serverContext(ks)).use { server ->
            WebDavTargets.open(server.location("KeepClear"), "Cloud", timeoutSeconds = 5) shouldBe
                WebDavCheck.CertificateRejected
            server.requests.size shouldBe 0
        }
    }

    @Test
    fun `a trusted certificate for another name is rejected`(): Unit = runBlocking {
        val ks = keystore("other", "dns:other.example")
        FakeDavServer(tls = serverContext(ks)).use { server ->
            WebDavTargets.open(server.location("KeepClear"), "Cloud", 5, trusting(ks)) shouldBe
                WebDavCheck.CertificateRejected
            server.requests.size shouldBe 0
        }
    }

    @Test
    fun `a trusted certificate for this address works`(): Unit = runBlocking {
        val ks = keystore("good", "ip:127.0.0.1")
        FakeDavServer(tls = serverContext(ks)).use { server ->
            WebDavTargets.open(server.location("KeepClear"), "Cloud", 5, trusting(ks))
                .shouldBeInstanceOf<WebDavCheck.Ok>()
        }
    }
}
