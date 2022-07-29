package com.example.ringman

import android.app.*
import android.app.NotificationManager.INTERRUPTION_FILTER_ALL
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.AudioManager.*
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

class RingmanService: Service() , SensorEventListener { // TODO - check power and memory use and optimize if necessary
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false
    private lateinit var audioManager: AudioManager
    private lateinit var sensorManager: SensorManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var displayManager: DisplayManager
    private var lightSensor: Sensor? = null
    private var accSensor: Sensor? = null
    private var luxRead = -1f
    private var g = floatArrayOf(0f, 0f, 0f)
    private var inclination = -1
    private var lastSensorUpdate = 0L


    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: service created")
        val notification = createNotification()
        startForeground(1, notification)
        setupSensorHardware()
    }

    private fun setupSensorHardware() {
        // set up ringer for use
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if ((accSensor == null) || (lightSensor == null)) {
            Log.e(TAG, "onCreate: sensors not found")
        }

        // setup display
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: executed with startId: $startId")
        if (intent != null) {
            when (intent.action) {
                Actions.ENABLE.name -> startService()
                Actions.DISABLE.name -> stopService()
            }
        } else {
            Log.e(TAG, "onStartCommand: null intent")
        }
        return START_STICKY
    }

    private fun startService() {
        if (isServiceStarted) return
        Log.i(TAG, "startService: starting foreground service")
        Toast.makeText(this, "Ringman enabled", Toast.LENGTH_SHORT).show()
        isServiceStarted = true
        setServiceState(this, ServiceState.ENABLED)

        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EndlessService::lock").apply {
                    acquire()
                }
            }

        if (isServiceStarted) {  // todo - launch in coroutine? Would that improve performance and resilience?
            displayManager.registerDisplayListener(displayListener, null)
            Log.i(TAG, "setupDisplay: listener registered")
        }
    }

    private fun stopService() {
        Log.i(TAG, "stopService: stopping")
        Toast.makeText(this, "Ringman disabled", Toast.LENGTH_SHORT).show()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            stopForeground(true)
            stopSelf()
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "stopService: service stopped without starting ${e.message}")
        }
        isServiceStarted = false
        setServiceState(this, ServiceState.DISABLED)
        stopRingman()
        displayManager.unregisterDisplayListener(displayListener)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            stopRingman()
            Log.i(TAG, "onDisplayChanged: state - $displayId")
            if (!isDisplayInUse()) {
                startRingman()
            } else {
                stopRingman()
                // TODO - decide on this, could wait like 10 sec, then turn off sound - BUT NEEDS ANOTHER THREAD!
//                Thread.sleep(1000 * 5)
//                setRingerState(CM_THRESHOLD + 1f)
////                audioManager.ringerMode = RINGER_MODE_VIBRATE   // ringer mode when device in use
//                audioManager.ringerMode = RINGER_MODE_SILENT   // ringer mode when device in use
            }
        }
        override fun onDisplayAdded(p0: Int) {}
        override fun onDisplayRemoved(p0: Int) {}
    }

    private fun isDisplayInUse(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
//        Log.i(TAG, "isDisplayInUse: isInteractice - ${powerManager.isInteractive}")
        return powerManager.isInteractive
    }

    fun startRingman() {
        lightSensor?.also { lightSensor ->
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI)
        }
        accSensor?.also { accSensor ->
            sensorManager.registerListener(this, accSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }
    fun stopRingman() {
        sensorManager.unregisterListener(this, lightSensor)
        sensorManager.unregisterListener(this, accSensor)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val actualTime = event.timestamp
        if(actualTime - lastSensorUpdate < 100000000) {
            return
        }
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            g = FloatArray(3)
            g = event.values.clone()
            val normOfG = sqrt((g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).toDouble())
            g[0] = (g[0] / normOfG).toFloat()
            g[1] = (g[1] / normOfG).toFloat()
            g[2] = (g[2] / normOfG).toFloat()
            inclination = Math.toDegrees(acos(g[2].toDouble())).roundToInt()
        }
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            luxRead = event.values[0]
        }
        if ((luxRead != -1f)&&(inclination != -1)) { // COULD MAKE CALL ONLY IF PARAMS PASS THRESHOLD
            Log.i(TAG, "onSensorChanged: light=$luxRead inclination=$inclination gravity=${g[1]}")
            setRingerState(luxRead, g, inclination)
        }
        lastSensorUpdate = actualTime
    }

    @Synchronized private fun setRingerState(light: Float, g: FloatArray, inclination: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.e(TAG, "setRingerState: build ${Build.VERSION.SDK_INT} too low")
            return
        }
        // shouldn't override DND
        if (notificationManager.currentInterruptionFilter != INTERRUPTION_FILTER_ALL) {
            Log.d(TAG, "setRingerState: interruptFilter is ${notificationManager.currentInterruptionFilter}")
            return
        }

//        Log.i(TAG, "setRingerState: audioManagerMode? ${audioManager.mode}")
//        Log.i(TAG, "setRingerState: display is on? ${isDisplayInUse()}")
        if (isDisplayInUse() || isCallActive()) {
            Log.i(TAG, "setRingerState: display in use")
            return
        }
        // CORE LOGIC
        if (deviceInPocket(light, g, inclination)) { // IN POCKET
            if (audioManager.ringerMode != RINGER_MODE_VIBRATE) {
                Log.i(TAG, "setRingerState (right bef): ${audioManager.ringerMode}")
                audioManager.ringerMode = RINGER_MODE_VIBRATE
                Log.i(TAG, "setRingerState: ${audioManager.ringerMode}")
            }
        }
        else {  // OUT OF POCKET
                if (audioManager.ringerMode != RINGER_MODE_NORMAL) {
                    Log.i(TAG, "setRingerState (right bef): ${audioManager.ringerMode}")
                    audioManager.ringerMode = RINGER_MODE_NORMAL
                    Log.i(TAG, "setRingerState: ${audioManager.ringerMode}")
                }
            }
        }

    private fun deviceInPocket(light: Float, g: FloatArray, inclination: Int) =
        (light < 10) && (g[1] < -0.6) && ((inclination > 75) && (inclination < 100))

    private fun isCallActive(): Boolean {
        return when (audioManager.mode) {
            MODE_IN_CALL->true
            MODE_IN_COMMUNICATION->true
            else -> false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRingman()
        displayManager.unregisterDisplayListener(displayListener)
        Log.i(TAG, "onDestroy: service destroyed")
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "RINGMAN_SERVICE_CHANNEL"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                notificationChannelId,
                "ringman-notifications-channel",
                NotificationManager.IMPORTANCE_LOW
            ).let {
                it.description = "Ringman service channel"
                it
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let {
                notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, FLAG_IMMUTABLE)
        }

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(
            this,
            notificationChannelId
        ) else Notification.Builder(this)

        return builder
            .setContentTitle("Ringman")
            .setContentText("Ringman handles ringer state")
            .setContentIntent(pendingIntent)
            .setTicker("Ringman handles ringer state")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(Notification.PRIORITY_LOW) // compatibility with < android 26
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent) {
        val restartServiceIntent = Intent(applicationContext, RingmanService::class.java).also {
            it.setPackage(packageName)
        }
        val restartServicePendingIntent: PendingIntent = PendingIntent.getService(this, 1, restartServiceIntent, FLAG_IMMUTABLE)
        applicationContext.getSystemService(Context.ALARM_SERVICE)
        val alarmService: AlarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
    }

    // unneeded inheritance
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        Log.i(TAG, "onAccuracyChanged: sensor=$sensor, accuracy=$accuracy")
        return
    }
    override fun onBind(p0: Intent?): IBinder? { return null }
}