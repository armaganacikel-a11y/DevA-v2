package tr.devlab.devasocks

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Kucuk halka buffer'li canli log kutusu.
 * Sunucu/service thread'lerinden log yazar; MainActivity dinler (UI thread'e gecirir).
 */
object LogBus {

    private const val MAX_LINES = 256

    private val lines = ArrayDeque<String>(MAX_LINES)
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val stampFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(raw: String) {
        lines.addLast("${stampFormat.format(Date())}  $raw")
        while (lines.size > MAX_LINES) lines.removeFirst()
        publishLocked()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        publishLocked()
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /** Basit threshold'lu ayni-mesaj spam filtresi: ayni mesaj arka arkaya gelirse tek satir. */
    @Synchronized
    fun logOnce(key: String, raw: String) {
        if (!recentOnce.containsKey(key) || nowMs() - (recentOnce[key] ?: 0L) > 3000) {
            recentOnce[key] = nowMs()
            log(raw)
        }
    }

    private val recentOnce = HashMap<String, Long>()

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun publishLocked() {
        val text = lines.joinToString("\n")
        listeners.forEach { listener ->
            runCatching { listener(text) }
        }
    }
}
