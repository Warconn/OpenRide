package dev.digitalducktape.openride.entertainment

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.digitalducktape.openride.MainActivity
import dev.digitalducktape.openride.appContainer
import dev.digitalducktape.openride.core.ride.RideSessionState
import dev.digitalducktape.openride.core.sensor.BikeMetrics
import dev.digitalducktape.openride.core.sensor.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * An opt-in floating, draggable workout HUD. The foreground service keeps the existing
 * process-wide RideSessionManager ticking when Plex/Kindle/another app is foregrounded.
 * It does not own, reset, or save the ride; returning to OpenRide and ending the ride does.
 *
 * "Hidden" means no overlay window at all, but the active session remains in the foreground
 * service (and the persistent notification provides a way back). On process death, the
 * existing session is NOT recoverable; this prototype is not a crash-safe ride recorder.
 */
class EntertainmentOverlayService : Service() {
    companion object {
        private const val ACTION_START = "dev.digitalducktape.openride.entertainment.START"
        private const val ACTION_SHOW = "dev.digitalducktape.openride.entertainment.SHOW"
        private const val EXTRA_VISIBLE = "visible"
        private const val CHANNEL_ID = "entertainment_ride"
        private const val NOTIFICATION_ID = 4107
        private const val PREFS = "entertainment_overlay"
        private const val BACKGROUND = 0xF2141B26.toInt()
        private const val ACCENT = 0xFF8FE8C8.toInt()

        fun start(context: Context, visible: Boolean) {
            val intent = Intent(context, EntertainmentOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VISIBLE, visible)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun show(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, EntertainmentOverlayService::class.java).setAction(ACTION_SHOW),
            )
        }
    }

    private data class Snapshot(
        val metrics: BikeMetrics,
        val elapsedSec: Int,
        val connection: ConnectionState,
        val heartRate: Int?,
        val state: RideSessionState,
    )

    private enum class Metric(val key: String, val title: String, val enabledByDefault: Boolean) {
        Time("time", "TIME", true),
        Cadence("cadence", "CADENCE", true),
        Power("power", "POWER", true),
        Resistance("resistance", "RESIST", true),
        HeartRate("heartrate", "HEART", false),
        Speed("speed", "SPEED", false),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var preferences: SharedPreferences
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var editing = false
    private var hidden = false
    private var latest: Snapshot? = null
    private val valueViews = mutableMapOf<Metric, TextView>()
    private var collector: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        val container = appContainer
        collector = scope.launch {
            combine(
                container.bikeDataSource.metrics,
                container.rideSessionManager.elapsedSec,
                container.bikeDataSource.connectionState,
                container.heartRateManager.bpm,
                container.rideSessionManager.state,
            ) { metrics, seconds, connection, heartRate, state ->
                Snapshot(metrics, seconds, connection, heartRate, state)
            }.collect { snapshot ->
                latest = snapshot
                if (snapshot.state is RideSessionState.Idle || snapshot.state is RideSessionState.Finished) {
                    stopSelf()
                } else {
                    updateValues()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        hidden = when (intent?.action) {
            ACTION_SHOW -> false
            ACTION_START -> !(intent?.getBooleanExtra(EXTRA_VISIBLE, true) ?: true)
            else -> hidden
        }
        startForeground(NOTIFICATION_ID, notification())
        if (hidden) removePanel() else showPanelIfAllowed()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removePanel()
        collector?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Active entertainment ride", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("OpenRide · Workout recording")
            .setContentText(if (hidden) "Metrics hidden. Tap to return to your ride." else "Live metrics over entertainment.")
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun showPanelIfAllowed() {
        if (!Settings.canDrawOverlays(this)) return
        if (panel != null) {
            rebuildPanel()
            return
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBackground(BACKGROUND, 18, 0x667D8B9A)
            elevation = dp(10).toFloat()
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt("x", dp(28))
            y = preferences.getInt("y", dp(110))
        }
        try {
            windowManager.addView(root, layout)
            panel = root
            params = layout
            rebuildPanel()
        } catch (_: Exception) {
            panel = null
            params = null
        }
    }

    private fun rebuildPanel() {
        val root = panel ?: return
        root.removeAllViews()
        valueViews.clear()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = label("⋮⋮  RIDE / LIVE", 13f, ACCENT, Typeface.BOLD).apply {
            setPadding(dp(4), dp(4), dp(18), dp(4))
            setOnTouchListener(dragListener())
            contentDescription = "Drag ride panel"
        }
        header.addView(handle)
        header.addView(control(if (editing) "Done" else "⚙") {
            editing = !editing
            collapsed = false
            rebuildPanel()
        })
        header.addView(control(if (collapsed) "▣" else "−") {
            collapsed = !collapsed
            editing = false
            rebuildPanel()
        })
        header.addView(control("×") {
            hidden = true
            removePanel()
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification())
        })
        root.addView(header)
        if (!collapsed) {
            if (editing) {
                root.addView(label("SHOW METRICS", 11f, 0xFF9EADBF.toInt(), Typeface.BOLD))
                Metric.entries.forEach { metric ->
                    root.addView(CheckBox(this).apply {
                        text = metric.title
                        setTextColor(Color.WHITE)
                        buttonTintList = ColorStateList.valueOf(ACCENT)
                        isChecked = preferences.getBoolean(metric.key, metric.enabledByDefault)
                        setOnCheckedChangeListener { _, checked ->
                            preferences.edit().putBoolean(metric.key, checked).apply()
                        }
                    })
                }
            } else {
                val metricsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                }
                Metric.entries.filter { preferences.getBoolean(it.key, it.enabledByDefault) }
                    .forEach { metric ->
                        val tile = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(8), dp(6), dp(8), dp(5))
                            gravity = Gravity.CENTER_HORIZONTAL
                            background = roundBackground(0xFF26313E.toInt(), 10)
                        }
                        val value = label("--", 22f, Color.WHITE, Typeface.BOLD).apply {
                            gravity = Gravity.CENTER
                        }
                        tile.addView(value)
                        tile.addView(label(metric.title, 10f, 0xFFBBC9D5.toInt(), Typeface.NORMAL))
                        metricsRow.addView(tile, LinearLayout.LayoutParams(dp(94), dp(68)).apply {
                            marginEnd = dp(6)
                        })
                        valueViews[metric] = value
                    }
                if (valueViews.isEmpty()) {
                    metricsRow.addView(label("Choose a metric in settings", 12f, Color.WHITE, Typeface.NORMAL))
                }
                root.addView(metricsRow)
                updateValues()
            }
        }
        // Re-measure content width after collapse/metric selection.
        params?.let { layout -> panel?.let { windowManager.updateViewLayout(it, layout) } }
    }

    private fun updateValues() {
        val reading = latest ?: return
        val available = reading.connection == ConnectionState.Connected
        val seconds = reading.elapsedSec
        val minutes = seconds / 60
        val remainder = seconds % 60
        val values = mapOf(
            Metric.Time to "%02d:%02d".format(minutes, remainder),
            Metric.Cadence to if (available) reading.metrics.cadenceRpm.toString() else "--",
            Metric.Power to if (available) reading.metrics.powerWatts.toString() else "--",
            Metric.Resistance to if (available) reading.metrics.resistancePercent.toString() else "--",
            Metric.HeartRate to (reading.heartRate?.toString() ?: "--"),
            Metric.Speed to if (available) "%.1f".format(reading.metrics.speedMph) else "--",
        )
        valueViews.forEach { (metric, view) -> view.text = values[metric] ?: "--" }
    }

    private fun removePanel() {
        val current = panel ?: return
        try { windowManager.removeView(current) } catch (_: IllegalArgumentException) { }
        panel = null
        params = null
        valueViews.clear()
    }

    private fun dragListener(): View.OnTouchListener = object : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val layout = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layout.x
                    initialY = layout.y
                    downX = event.rawX
                    downY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = (initialX + (event.rawX - downX).toInt()).coerceAtLeast(0)
                    layout.y = (initialY + (event.rawY - downY).toInt()).coerceAtLeast(0)
                    panel?.let { windowManager.updateViewLayout(it, layout) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    preferences.edit().putInt("x", layout.x).putInt("y", layout.y).apply()
                    view.performClick()
                    return true
                }
            }
            return false
        }
    }

    private fun control(text: String, action: () -> Unit): TextView =
        label(text, 17f, Color.WHITE, Typeface.NORMAL).apply {
            setPadding(dp(12), dp(3), dp(12), dp(3))
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }

    private fun label(text: String, sizeSp: Float, color: Int, weight: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            setTypeface(Typeface.DEFAULT, weight)
        }

    private fun roundBackground(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
