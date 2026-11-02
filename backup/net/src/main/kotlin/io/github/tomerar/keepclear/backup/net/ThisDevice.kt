// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tomerar.keepclear.backup.net

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import javax.net.SocketFactory

/**
 * D3 (owner review 2026-11-02): only tests may connect to a server on the device itself. Marks [TestsOnThisDevice];
 * using it needs `@OptIn(ThisDeviceInTests::class)`, which is a compile **error** without it, and build-logic's
 * `checkForbiddenApis` fails if any main (non-test) class of any module refers to [TestsOnThisDevice] at all.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Only tests may connect to a server on this device (D3): a backup there is a copy on the same phone.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class ThisDeviceInTests

/**
 * Which servers a network backup place may connect to (D3). The app only ever uses [NetworkOnly]: a server on the
 * phone itself (`localhost`, `127.0.0.1`, `::1`, or one of the phone's own addresses) would be a backup on the same
 * phone, so it is refused. Tests talk to a Samba and a Nextcloud on the test machine's loopback, so they pass
 * [TestsOnThisDevice] explicitly.
 */
sealed class HostRule(val allowsThisDevice: Boolean) {
    /** Every place set up in the app: never this device. */
    data object NetworkOnly : HostRule(false)
}

/** Tests only (see [ThisDeviceInTests]): servers on the test machine itself are allowed, and plain http to them. */
@ThisDeviceInTests
data object TestsOnThisDevice : HostRule(true)

/** The address is this device itself (D3). An [UnknownHostException] so OkHttp and smbj pass it on unchanged. */
class ThisDeviceException(host: String) : UnknownHostException("$host is this device; a backup must go elsewhere")

/**
 * D3: tells whether a host is this device. Two layers:
 * - [isLocalName] / [isAmbiguousNumber]: checks on the text the user typed, without any lookup (the forms can show
 *   a plain line at once).
 * - [socketFactory]: every connection the network targets open goes through a socket that checks the **actual
 *   address** right before connecting, so a name that resolves to the phone, or one of the phone's own addresses
 *   (Wi-Fi, hotspot, VPN), is refused too, whatever the name said.
 */
object ThisDevice {
    private val strictIpv4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
    private val numericLabel = Regex("""^(0[xX][0-9a-fA-F]*|\d+)$""")

    /**
     * True for a name or literal address that always means this device: `localhost` (and `*.localhost`, RFC 6761),
     * a loopback address (`127.0.0.0/8`, `::1`) or the "any" address (`0.0.0.0`, `::`). No DNS lookup.
     */
    fun isLocalName(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
        if (h == "localhost" || h.endsWith(".localhost")) return true
        val literal = literal(h) ?: return false
        return literal.isLoopbackAddress || literal.isAnyLocalAddress
    }

    /**
     * True for numbers that are not a plain `a.b.c.d` address, such as `127.1`, `2130706433`, `0x7f.1` or `010.0.0.1`:
     * the system reads them as addresses in surprising ways (octal, hex, short forms), so KeepClear refuses them.
     * A real host name never ends in a label that is only digits.
     */
    fun isAmbiguousNumber(host: String): Boolean {
        val h = host.trim().removeSuffix(".")
        if (h.isEmpty() || ':' in h || strictIpv4.matches(h)) return false
        val labels = h.split('.')
        return labels.last().all { it.isDigit() } || labels.all { numericLabel.matches(it) }
    }

    /** A strict IPv4 or an IPv6 literal, parsed without any lookup; null for a name. */
    private fun literal(h: String): InetAddress? = when {
        strictIpv4.matches(h) -> InetAddress.getByAddress(h.split('.').map { it.toInt().toByte() }.toByteArray())

        // Java parses a text with ':' as an IPv6 literal and never looks it up.
        ':' in h && h.all { it.isLetterOrDigit() || it == ':' || it == '.' } -> try {
            InetAddress.getByName(h)
        } catch (_: UnknownHostException) {
            null
        }

        else -> null
    }

    /** The addresses of this device's own network interfaces (Wi-Fi, mobile, hotspot, VPN); empty if unknown. */
    fun ownAddresses(): Set<ByteArrayKey> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { it.inetAddresses.toList() }
            .map { ByteArrayKey(it.address) }
            .toSet()
    } catch (_: Exception) {
        // SocketException, or a SecurityException on a locked-down device: the loopback check still applies.
        emptySet()
    }

    /** True when [address] is this device: loopback, the "any" address, or one of [own]. */
    fun isThisDevice(address: InetAddress, own: Set<ByteArrayKey> = ownAddresses()): Boolean =
        address.isLoopbackAddress || address.isAnyLocalAddress || ByteArrayKey(address.address) in own

    /**
     * A socket factory whose sockets refuse to connect to this device unless [rule] allows it (the real address,
     * after DNS). [connectTimeoutMillis] bounds connecting (0 = the system's).
     */
    fun socketFactory(
        rule: HostRule,
        connectTimeoutMillis: Int = 0,
        own: () -> Set<ByteArrayKey> = ::ownAddresses,
    ): SocketFactory = GuardedSocketFactory(rule, connectTimeoutMillis, own)

    /** An address's bytes with value equality (IPv6 scope ids ignored). */
    class ByteArrayKey(val bytes: ByteArray) {
        override fun equals(other: Any?) = other is ByteArrayKey && other.bytes.contentEquals(bytes)

        override fun hashCode() = bytes.contentHashCode()
    }
}

private class GuardedSocketFactory(
    private val rule: HostRule,
    private val connectTimeoutMillis: Int,
    private val own: () -> Set<ThisDevice.ByteArrayKey>,
) : SocketFactory() {
    override fun createSocket(): Socket = GuardedSocket(rule, own)

    override fun createSocket(host: String, port: Int): Socket =
        connected(InetSocketAddress(host, port), host)

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        throw IOException("binding a local address is not supported")

    override fun createSocket(host: InetAddress, port: Int): Socket =
        connected(InetSocketAddress(host, port), host.hostAddress)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        throw IOException("binding a local address is not supported")

    private fun connected(address: InetSocketAddress, host: String): Socket {
        if (address.isUnresolved) throw UnknownHostException(host)
        return GuardedSocket(rule, own).apply {
            try {
                connect(address, connectTimeoutMillis)
            } catch (e: IOException) {
                close()
                throw e
            }
        }
    }
}

/** Checks the address it is about to connect to (D3); everything else is a plain socket. */
private class GuardedSocket(
    private val rule: HostRule,
    private val own: () -> Set<ThisDevice.ByteArrayKey>,
) : Socket() {
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        val address = (endpoint as? InetSocketAddress)?.takeUnless { it.isUnresolved }?.address
            ?: throw UnknownHostException(endpoint.toString())
        if (!rule.allowsThisDevice && ThisDevice.isThisDevice(address, own())) {
            close()
            throw ThisDeviceException(address.hostAddress)
        }
        super.connect(endpoint, timeout)
    }
}
