package uk.co.steadydots

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.TileService
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * Foreground service that owns the overlay window and the motion sensors.
 *
 * Sensor maths, in short:
 *  1. TYPE_GRAVITY tells us which way is "up" in the phone's own axes.
 *  2. We assume you're facing the direction of travel with the screen facing you, so
 *     "forward" is roughly "into the screen" (-Z) plus "towards the top of the screen",
 *     flattened onto the horizontal plane. That works whether the phone is upright in a
 *     mount, tilted in your hand, or lying flat on your lap.
 *  3. "Right" = forward × up.
 *  4. TYPE_LINEAR_ACCELERATION (gravity already removed) is projected onto forward/right,
 *     smoothed to remove road buzz, and handed to the view.
 */
class OverlayService : Service(), SensorEventListener {

    companion object {
        const val ACTION_STOP = "uk.co.steadydots.STOP"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1

        /** Low-pass factor range for acceleration (0..1). Lower = smoother but laggier. */
        private const val MIN_SMOOTHING = 0.08f
        private const val MAX_SMOOTHING = 0.5f

        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var sensorManager: SensorManager
    private var overlay: DotsOverlayView? = null

    private val gravity = floatArrayOf(0f, 9.81f, 0f)
    private val linear = FloatArray(3)
    private var hasGravitySensor = false
    private var hasLinearSensor = false
    private var displayRotation = Surface.ROTATION_0

    /** Current low-pass factor, set from the Responsiveness slider. */
    private var smoothing = 0.33f

    private fun applyConfig() {
        val cfg = Prefs.load(this)
        smoothing = MIN_SMOOTHING + (MAX_SMOOTHING - MIN_SMOOTHING) * cfg.response
        overlay?.applyConfig(cfg)
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyConfig()
    }

    /** Stop reading sensors while the screen is off to save battery. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> sensorManager.unregisterListener(this@OverlayService)
                Intent.ACTION_SCREEN_ON -> registerSensors()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WindowManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        addOverlay()
        applyConfig()
        displayRotation = currentRotation()
        registerSensors()

        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener)
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshTile()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        displayRotation = currentRotation()
    }

    override fun onDestroy() {
        isRunning = false
        sensorManager.unregisterListener(this)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        runCatching { unregisterReceiver(screenReceiver) }
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        refreshTile()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- overlay window

    private fun addOverlay() {
        val view = DotsOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Android 12+ blocks touches from passing through untrusted overlays whose
            // window alpha is above 0.8. Keep it at 0.8 so you can still use the phone.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "SteadyDots"
        }
        windowManager.addView(view, params)
        overlay = view
    }

    // ---------------------------------------------------------------- sensors

    private fun registerSensors() {
        sensorManager.unregisterListener(this)
        val grav = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val lin = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        hasGravitySensor = grav != null
        hasLinearSensor = lin != null

        if (grav != null && lin != null) {
            sensorManager.registerListener(this, grav, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, lin, SensorManager.SENSOR_DELAY_GAME)
        } else {
            // Budget phones without fused sensors: derive both from the raw accelerometer
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity[0] = v[0]; gravity[1] = v[1]; gravity[2] = v[2]
                return
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                for (i in 0..2) linear[i] += smoothing * (v[i] - linear[i])
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Slow low-pass = gravity, remainder = linear acceleration
                for (i in 0..2) {
                    gravity[i] += 0.05f * (v[i] - gravity[i])
                    linear[i] += smoothing * ((v[i] - gravity[i]) - linear[i])
                }
            }
            else -> return
        }
        pushToView()
    }

    private fun pushToView() {
        val view = overlay ?: return

        // 1. Up direction (gravity sensor reports a vector pointing up when the phone is still)
        val up = normalize(gravity) ?: return

        // 2. Which device axis points to the top of the screen, given current rotation
        val screenUp = when (displayRotation) {
            Surface.ROTATION_90 -> floatArrayOf(1f, 0f, 0f)
            Surface.ROTATION_180 -> floatArrayOf(0f, -1f, 0f)
            Surface.ROTATION_270 -> floatArrayOf(-1f, 0f, 0f)
            else -> floatArrayOf(0f, 1f, 0f)
        }
        val intoScreen = floatArrayOf(0f, 0f, -1f)

        // 3. Forward = (screen-top + into-screen) flattened onto the horizontal plane
        val fRaw = add(flatten(screenUp, up), flatten(intoScreen, up))
        val forward = normalize(fRaw) ?: return
        val right = cross(forward, up)

        // 4. Project acceleration onto the car's axes
        val aForward = dot(linear, forward)
        val aRight = dot(linear, right)

        // Dots move with the force you FEEL, which is opposite to the acceleration.
        // Screen x+ = right, screen y+ = down (down = backwards).
        view.setAcceleration(-aRight, aForward)
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }

    // ---------------------------------------------------------------- vector helpers

    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun add(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    /** Remove the vertical component of v, leaving its horizontal part. */
    private fun flatten(v: FloatArray, up: FloatArray): FloatArray {
        val d = dot(v, up)
        return floatArrayOf(v[0] - d * up[0], v[1] - d * up[1], v[2] - d * up[2])
    }

    private fun normalize(v: FloatArray): FloatArray? {
        val len = sqrt(dot(v, v))
        if (len < 0.1f) return null
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    // ---------------------------------------------------------------- notification & tile

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Motion dots", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the motion-cue dots are on screen" }
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dots)
            .setContentTitle("Motion dots are on")
            .setContentText("Tap to adjust, or stop them here.")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, DotsTileService::class.java))
    }
}
