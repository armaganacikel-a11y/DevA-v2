package tr.devlab.devasocks

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Dev-A v2 SOCKS5 sunucusu (RFC 1928).
 *
 * - TCP CONNECT  : istek basina iki pump thread'i (full-duplex relay)
 * - UDP ASSOCIATE: kalici relay (DatagramSocket), NAT benzeri dst->client esleme,
 *                  control kanali acik kaldigi + bos kalma suresi (idle) bitmedigi surece yasar
 * - BIND         : desteklenmez (REP=0x07)
 *
 * Tum soketler stop()'ta kapatilir; thread'ler soket kapanma istisnasindan kendiliginden cikar.
 */
class Socks5Server(
    private val port: Int,
    private val onConnectionsChanged: (Int) -> Unit = {}
) {

    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val running = AtomicBoolean(false)
    private val connectionCount = AtomicInteger(0)
    private val openTcp = Collections.synchronizedList(mutableListOf<Socket>())
    private val openUdp = Collections.synchronizedList(mutableListOf<DatagramSocket>())

    val isActive: Boolean get() = running.get()
    val activeConnections: Int get() = connectionCount.get()

    // ---------- RFC 1928 sabitleri ----------
    private val VER = 5
    private val NO_AUTH = 0x00
    private val CMD_CONNECT = 0x01
    private val CMD_UDP_ASSOCIATE = 0x03
    private val ATYP_IPV4 = 0x01
    private val ATYP_DOMAIN = 0x03
    private val ATYP_IPV6 = 0x04

    private val REP_SUCCESS = 0x00
    private val REP_GENERAL = 0x01
    private val REP_HOST_UNREACHABLE = 0x04
    private val REP_REFUSED = 0x05
    private val REP_TTL = 0x06
    private val REP_CMD_NOT_SUPPORTED = 0x07
    private val REP_ATYP_NOT_SUPPORTED = 0x08

    private val HANDSHAKE_TIMEOUT_MS = 15_000
    private val CONNECT_TIMEOUT_MS = 12_000
    private val UDP_POLL_MS = 1_000
    private val UDP_IDLE_MS = 300_000L          // bos kalirsa association kapanir (kalici NAT penceresi)

    // ================= lifecycle =================

    @Synchronized
    fun start() {
        if (running.get()) return
        val server = ServerSocket()
        server.reuseAddress = true
        try {
            server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        } catch (e: IOException) {
            try { server.close() } catch (_: IOException) {}
            LogBus.log("[!] Port " + port + " dinlenemedi: " + (e.message ?: e.javaClass.simpleName))
            throw e
        }
        listener = server
        running.set(true)
        acceptThread = thread(name = "SOCKS-ACCEPT") { acceptLoop(server) }
        LogBus.log("[+] SOCKS5 dinleme basladi: 0.0.0.0:$port")
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { listener?.close() } catch (_: IOException) {}
        listener = null

        val tcp = openTcp.toList()
        openTcp.clear()
        tcp.forEach { closeQuietly(it) }

        val udp = openUdp.toList()
        openUdp.clear()
        udp.forEach { closeQuietly(it) }

        publishCount(0)
        LogBus.log("[-] SOCKS5 dinleme durduruldu")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client: Socket = try {
                server.accept()
            } catch (e: IOException) {
                if (running.get()) {
                    LogBus.log("[!] accept hatasi: ${e.message ?: "-"}")
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                    continue
                }
                break
            }
            connectionCount.incrementAndGet()
            publishCount(connectionCount.get())
            registerTcp(client)
            thread(name = "SOCKS-CLIENT") { handle(client) }
        }
    }

    // ================= istemci kabul + el sikisma =================

    private fun handle(client: Socket) {
        // UDP ASSOCIATE'te kontrol soketi monitorControl() tarafindan yonetilir;
        // bu durumda manage etmeyi buraya birakmayiz (cifte kapatma/sayac hatasi olmasin).
        var managedHere = true
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val input = client.getInputStream().buffered()
            val output = client.getOutputStream().buffered()

            // 1) Greeting: VER | NMETHODS | METHODS
            if (input.read() != 5) throw IOException("ilk byte VER(5) degil")
            val nmethods = input.read()
            if (nmethods !in 1..255) throw IOException("NMETHODS gecersiz")
            val methods = ByteArray(nmethods).also { input.readFully(it) }
            if (methods.none { it == NO_AUTH.toByte() }) {
                output.write(byteArrayOf(5, 0xFF.toByte()))
                output.flush()
                throw IOException("istemci NO-AUTH duyurmadı")
            }
            output.write(byteArrayOf(5, NO_AUTH))
            output.flush()

            // 2) Request: VER | CMD | RSV | ATYP | DST.ADDR | DST.PORT
            val head = ByteArray(4).also { input.readFully(it) }
            if (head[0] != VER.toByte()) throw IOException("istek VER(5) degil")
            val cmd = head[1].toInt() and 0xFF
            val atyp = head[3].toInt() and 0xFF
            val (host, ip) = readAddress(input, atyp)
            val dport = readPort(input)

            when (cmd) {
                CMD_CONNECT -> {
                    LogBus.log("[>] CONNECT $host:" + dport)
                    serveConnect(client, input, output, host, ip, dport)
                }
                CMD_UDP_ASSOCIATE -> {
                    LogBus.log("[>] UDP ASSOCIATE (dst=" + (ip?.hostAddress ?: host) + ":" + dport + ")")
                    managedHere = false
                    serveUdpAssociate(client, output, ip, dport)
                }
                else -> {
                    reply(output, REP_CMD_NOT_SUPPORTED, null, 0)
                    throw IOException("CMD=$cmd desteklenmiyor")
                }
            }
        } catch (e: Exception) {
            // Istemci kapandiginda gelen EOF(SocketException/IO) sessiz gecilir.
            if (e !is SocketException) {
                val msg = e.message ?: e.javaClass.simpleName
                if (e is IOException && (e.message?.contains("kapandı") == true || e.message?.contains("kapandi") == true)) {
                    // normal istemci kapanisi
                } else {
                    LogBus.logOnce("hata:" + msg.hashCode(), "[!] " + msg)
                }
            }
        } finally {
            if (managedHere) {
                closeQuietly(client)
                unregisterTcp(client)
                connectionCount.decrementAndGet()
                publishCount(connectionCount.get())
            }
        }
    }

    private fun serveConnect(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        host: String,
        ip: InetAddress?,
        port: Int
    ) {
        val target: InetAddress
        try {
            target = ip ?: InetAddress.getByName(host.removeBrackets())
        } catch (_: UnknownHostException) {
            reply(output, REP_HOST_UNREACHABLE, null, 0)
            LogBus.log("[!] DNS cozemedi: " + host)
            return
        }

        val remote = Socket()
        var connected = false
        try {
            try {
                remote.connect(InetSocketAddress(target, port), CONNECT_TIMEOUT_MS)
                connected = true
            } catch (e: SocketTimeoutException) {
                reply(output, REP_TTL, null, 0)
                LogBus.log("[!] CONNECT zaman asimi: " + host + ":" + port)
            } catch (e: SocketException) {
                // Genelde Connection refused
                reply(output, REP_REFUSED, null, 0)
                LogBus.log("[!] CONNECT reddedildi: " + host + ":" + port)
            } catch (e: IOException) {
                reply(output, REP_GENERAL, null, 0)
                LogBus.log("[!] CONNECT basarisiz: " + host + ":" + port + " (" + (e.message ?: "-") + ")")
            }
            if (!connected) return

            remote.tcpNoDelay = true
            registerTcp(remote)
            reply(output, REP_SUCCESS, pickBindAddress(remote.localAddress), remote.port)
            // Relay fazinda bloklu okuma
            client.soTimeout = 0
            LogBus.log("[+] CONNECT OK " + host + ":" + port)

            // Uc yonlu relay: client->remote bu thread'de, remote->client yardimci thread'de.
            thread(name = "SOCKS-RELAY-UP") {
                pump(remote.getInputStream(), client.getOutputStream(), remote)
            }
            pump(input, remote.getOutputStream(), remote)
        } finally {
            closeQuietly(remote)
            unregisterTcp(remote)
        }
    }

    /** b soketi kapanana dek from->to veri pompalar. */
    private fun pump(from: InputStream, to: OutputStream, b: Socket) {
        val buf = ByteArray(64 * 1024)
        try {
            while (running.get()) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        } finally {
            closeQuietly(b)
            unregisterTcp(b)
        }
    }

    // ================= UDP ASSOCIATE (RFC 1928 4. secim 3) =================

    private fun serveUdpAssociate(client: Socket, output: OutputStream, ip: InetAddress?, dport: Int) {
        val relay = DatagramSocket(null)
        val outbound = DatagramSocket(null)
        try {
            relay.reuseAddress = true
            relay.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            relay.soTimeout = UDP_POLL_MS

            outbound.reuseAddress = true
            outbound.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            outbound.soTimeout = UDP_POLL_MS

            // BND.ADDR: istemcinin bize baglandigi IP (adb arkasinda 0.0.0.0 dusebilir ->
            // o zaman telefonun site-local WiFi IP'si verilir).
            val bnd = pickBindAddress(client.localAddress) ?: InetAddress.getLoopbackAddress()
            reply(output, REP_SUCCESS, bnd, relay.localPort)

            registerUdp(relay)
            registerUdp(outbound)

            val lastActive = AtomicLong(now())
            val routes = ConcurrentHashMap<String, InetSocketAddress>()

            thread(name = "SOCKS-UDP-DN") { udpDownstreamLoop(relay, outbound, routes, lastActive) }
            thread(name = "SOCKS-UDP-UP") { udpUpstreamLoop(outbound, relay, routes, lastActive) }
            thread(name = "SOCKS-UDP-CTL") { monitorControl(client) }

            LogBus.log(
                "[+] UDP relay acik: " + bnd.hostAddress + ":" + relay.localPort +
                    " (istenilen dst=" + (ip?.hostAddress ?: "0.0.0.0") + ":" + dport + ")"
            )
        } catch (e: Exception) {
            closeQuietly(relay)
            unregisterUdp(relay)
            closeQuietly(outbound)
            unregisterUdp(outbound)
            try {
                reply(output, REP_GENERAL, null, 0)
            } catch (_: IOException) {
            }
            closeQuietly(client)
            unregisterTcp(client)
            connectionCount.decrementAndGet()
            publishCount(connectionCount.get())
            LogBus.log("[!] UDP ASSOCIATE kurulamadi: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** Kontrol kanalini canli tutar; istemci kapandiginda sayaci dusurur. Relay idle suresine kadar yasar. */
    private fun monitorControl(control: Socket) {
        try {
            val input = control.getInputStream()
            val buf = ByteArray(256)
            while (input.read(buf) >= 0) {
                // kontrol kanalindan veri beklenmez; sadece canlilik
            }
        } catch (_: IOException) {
        }
        closeQuietly(control)
        unregisterTcp(control)
        connectionCount.decrementAndGet()
        publishCount(connectionCount.get())
        LogBus.log("[-] UDP kontrol kanali kapandi (relay idle suresine kadar yasar)")
    }

    /** istemci -> hedef */
    private fun udpDownstreamLoop(
        relay: DatagramSocket,
        outbound: DatagramSocket,
        routes: ConcurrentHashMap<String, InetSocketAddress>,
        lastActive: AtomicLong
    ) {
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        try {
            while (running.get()) {
                try {
                    relay.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    if (idleExpired(lastActive)) break
                    continue
                }
                if (!running.get()) break
                lastActive.set(now())
                if (pkt.length < 4) continue // header yen - dusur
                if (buf[2] != 0.toByte()) {  // FRAG != 0 desteklenmiyor
                    LogBus.logOnce("frag", "[!] UDP fragmantasyon desteklenmiyor, paket dusuruldu")
                    continue
                }
                val dst = parsePacketAddress(buf, pkt.length) ?: continue
                val (addr, dport, offset) = dst
                routes[key(addr, dport)] = InetSocketAddress(pkt.address, pkt.port)
                if (routes.size > 128) routes.clear() // basit tasma korumasi
                try {
                    outbound.send(DatagramPacket(buf, offset, pkt.length - offset, addr, dport))
                } catch (e: IOException) {
                    LogBus.logOnce("udpsend:" + addr.hostAddress, "[!] UDP gonderilemedi " + addr.hostAddress + ":" + dport)
                }
            }
        } catch (_: SocketException) {
            // stop() soketleri kapatti
        } catch (_: IOException) {
        } finally {
            closeQuietly(outbound)
            unregisterUdp(outbound)
            closeQuietly(relay)
            unregisterUdp(relay)
        }
    }

    /** hedef -> istemci */
    private fun udpUpstreamLoop(
        outbound: DatagramSocket,
        relay: DatagramSocket,
        routes: ConcurrentHashMap<String, InetSocketAddress>,
        lastActive: AtomicLong
    ) {
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        try {
            while (running.get()) {
                try {
                    outbound.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    if (idleExpired(lastActive)) break
                    continue
                }
                if (!running.get()) break
                lastActive.set(now())
                val ep = routes[key(pkt.address, pkt.port)] ?: continue // bilmeyen kaynak - dusur
                val header = udpHeader(pkt.address, pkt.port)
                val out = ByteArray(header.size + pkt.length)
                System.arraycopy(header, 0, out, 0, header.size)
                System.arraycopy(buf, 0, out, header.size, pkt.length)
                try {
                    relay.send(DatagramPacket(out, out.size, ep))
                } catch (e: IOException) {
                    LogBus.logOnce("udpback", "[!] UDP yanit istemciye donmedi: " + (e.message ?: "-"))
                }
            }
        } catch (_: SocketException) {
        } catch (_: IOException) {
        } finally {
            closeQuietly(outbound)
            unregisterUdp(outbound)
            closeQuietly(relay)
            unregisterUdp(relay)
        }
    }

    private fun idleExpired(lastActive: AtomicLong): Boolean = now() - lastActive.get() > UDP_IDLE_MS

    private fun key(addr: InetAddress, port: Int): String = (addr.hostAddress ?: "?") + ":" + port

    /** [RSV 2 | FRAG 1 | ATYP 1 | ADDR n | PORT 2] -> (addr, port, dataOffset) */
    private fun parsePacketAddress(buf: ByteArray, len: Int): Triple<InetAddress, Int, Int>? {
        val atyp = buf[3].toInt() and 0xFF
        return when (atyp) {
            ATYP_IPV4 -> if (len < 10) null else {
                Triple(InetAddress.getByAddress(buf.copyOfRange(4, 8)), portAt(buf, 8), 10)
            }
            ATYP_DOMAIN -> {
                if (len < 7) return null
                val alen = buf[4].toInt() and 0xFF
                val portOffset = 5 + alen
                if (len < portOffset + 2) return null
                val domain = String(buf, 5, alen, Charsets.US_ASCII).trim()
                val addr = try {
                    InetAddress.getByName(domain)
                } catch (_: Exception) {
                    return null
                }
                Triple(addr, portAt(buf, portOffset), portOffset + 2)
            }
            ATYP_IPV6 -> if (len < 22) null else {
                Triple(InetAddress.getByAddress(buf.copyOfRange(4, 20)), portAt(buf, 20), 22)
            }
            else -> null
        }
    }

    private fun udpHeader(addr: InetAddress, port: Int): ByteArray {
        val raw = addr.address
        val header = ByteArray(4 + raw.size + 2)
        header[3] = if (raw.size == 4) ATYP_IPV4.toByte() else ATYP_IPV6.toByte()
        System.arraycopy(raw, 0, header, 4, raw.size)
        header[4 + raw.size] = (port ushr 8).toByte()
        header[5 + raw.size] = (port and 0xFF).toByte()
        return header
    }

    // ================= ortak yardimcilar =================

    /** ATYP'e gore DST.ADDR okur -> (host-ad, cozulmus-IP?) */
    private fun readAddress(input: InputStream, atyp: Int): Pair<String, InetAddress?> = when (atyp) {
        ATYP_IPV4 -> {
            val raw = ByteArray(4).also { input.readFully(it) }
            val addr = InetAddress.getByAddress(raw)
            Pair(addr.hostAddress ?: "ip", addr)
        }
        ATYP_DOMAIN -> {
            val len = input.read()
            if (len !in 1..255) throw IOException("domain uzunlugu gecersiz")
            val domain = String(ByteArray(len).also { input.readFully(it) }, Charsets.US_ASCII).trim()
            Pair(domain, null)
        }
        ATYP_IPV6 -> {
            val raw = ByteArray(16).also { input.readFully(it) }
            val addr = InetAddress.getByAddress(raw)
            Pair(addr.hostAddress ?: "ipv6", addr)
        }
        else -> throw IOException("ATYP=$atyp desteklenmiyor")
    }

    private fun readPort(input: InputStream): Int {
        val raw = ByteArray(2).also { input.readFully(it) }
        return ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
    }

    /** reply(): VER | REP | RSV | ATYP | BND.ADDR | BND.PORT */
    private fun reply(output: OutputStream, code: Int, bnd: InetAddress?, bndPort: Int) {
        val addr = bnd ?: InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
        val raw = addr.address
        val reply = ByteArray(4 + raw.size + 2)
        reply[0] = VER.toByte()
        reply[1] = code.toByte()
        reply[2] = 0
        reply[3] = if (raw.size == 4) ATYP_IPV4.toByte() else ATYP_IPV6.toByte()
        System.arraycopy(raw, 0, reply, 4, raw.size)
        reply[4 + raw.size] = (bndPort ushr 8).toByte()
        reply[5 + raw.size] = (bndPort and 0xFF).toByte()
        output.write(reply)
        output.flush()
    }

    private fun pickBindAddress(preferred: InetAddress?): InetAddress? {
        if (preferred != null && !preferred.isAnyLocalAddress) return preferred
        return firstSiteLocalIp()
    }

    private fun firstSiteLocalIp(): InetAddress? = try {
        val nics = Collections.list(NetworkInterface.getNetworkInterfaces())
        nics.asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    } catch (_: Exception) {
        null
    }

    private fun publishCount(count: Int) {
        try { onConnectionsChanged(count) } catch (_: Exception) {}
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun registerTcp(socket: Socket) { openTcp.add(socket) }
    private fun unregisterTcp(socket: Socket) { openTcp.remove(socket) }
    private fun registerUdp(socket: DatagramSocket) { openUdp.add(socket) }
    private fun unregisterUdp(socket: DatagramSocket) { openUdp.remove(socket) }

    private fun closeQuietly(socket: Socket) {
        try { socket.close() } catch (_: IOException) {}
    }

    private fun closeQuietly(socket: DatagramSocket) {
        try { socket.close() } catch (_: Exception) {}
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = read(buffer, offset, buffer.size - offset)
            if (n < 0) throw IOException("baglanti erken kapandi")
            offset += n
        }
    }

    private fun String.removeBrackets(): String =
        if (startsWith("[") && endsWith("]")) substring(1, length - 1) else this

    companion object {
        const val TAG = "Socks5Server"
    }
}
