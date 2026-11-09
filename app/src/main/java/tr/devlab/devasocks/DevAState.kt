package tr.devlab.devasocks

import java.util.concurrent.atomic.AtomicInteger

/** UI ve servis arasinda paylasilan calisma durumu. */
object DevAState {
    const val DEFAULT_PORT = 8000

    /** Telefonda dinlenen SOCKS5 portu (RFC 1928). */
    @Volatile
    var port: Int = DEFAULT_PORT

    /** Foreground service su anda yayinda mi? */
    @Volatile
    var running: Boolean = false

    /** Su anda acik olan SOCKS5 istemci baglantisi sayisi. */
    val activeConnections = AtomicInteger(0)
}
