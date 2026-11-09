package tr.devlab.devasocks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Mini UI: start/stop, port gostergesi, aktif baglaci sayaci ve canli log penceresi.
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvPort: TextView
    private lateinit var tvConn: TextView
    private lateinit var btnToggle: Button
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView

    private val logListener: (String) -> Unit = { text ->
        runOnUiThread { applyLog(text) }
    }

    private val uiTick = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvPort = findViewById(R.id.tvPort)
        tvConn = findViewById(R.id.tvConn)
        btnToggle = findViewById(R.id.btnToggle)
        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)

        btnToggle.setOnClickListener {
            if (DevAState.running) stopProxy() else startProxy()
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener { LogBus.clear() }

        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        LogBus.addListener(logListener)
        applyLog(LogBus.snapshot())
        handler.post(uiTick)
    }

    override fun onStop() {
        super.onStop()
        LogBus.removeListener(logListener)
        handler.removeCallbacks(uiTick)
    }

    private fun refreshState() {
        val running = DevAState.running
        tvStatus.text = getString(if (running) R.string.status_running else R.string.status_stopped)
        tvStatus.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.accent else R.color.danger)
        )
        tvPort.text = getString(R.string.port_value, DevAState.port)
        tvConn.text = getString(R.string.conn_value, DevAState.activeConnections.get())
        btnToggle.text = getString(if (running) R.string.stop else R.string.start)
        btnToggle.setBackgroundResource(
            if (running) R.drawable.bg_button_stop else R.drawable.bg_button
        )
    }

    private fun applyLog(text: String) {
        tvLog.text = text
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERMISSION_REQUEST
            )
        }
    }

    private fun startProxy() {
        requestNotificationPermissionIfNeeded()
        LogBus.log("[i] Baslatiliyor ...")
        ContextCompat.startForegroundService(
            this,
            Intent(this, SocksService::class.java).setAction(SocksService.ACTION_START)
        )
    }

    private fun stopProxy() {
        startService(
            Intent(this, SocksService::class.java).setAction(SocksService.ACTION_STOP)
        )
    }

    companion object {
        private const val PERMISSION_REQUEST = 1001
    }
}
