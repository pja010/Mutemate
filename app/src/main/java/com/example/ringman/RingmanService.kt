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
import android.view.Display.*
import android.widget.Toast

private const val CM_THRESHOLD = 0.1f
class RingmanService: Service() , SensorEventListener { // TODO - check power and memory use and optimize if necessary
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false
    private lateinit var audioManager: AudioManager
    private lateinit var sensorManager: SensorManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var displayManager: DisplayManager
    private var proximity: Sensor? = null

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

        // set up proximity sensor access
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        if (sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null) {
            proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        } else {
            Log.e(TAG, "onCreate: proximity-sensor not found")
        }

        // setup display
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        } else {
            Log.e(TAG, "setupDisplay: build version too low")
        }
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
//                Thread.sleep(1000 * 10)
//                setRingerState(CM_THRESHOLD + 1f)
////                audioManager.ringerMode = RINGER_MODE_VIBRATE   // ringer mode when device in use
//                audioManager.ringerMode = RINGER_MODE_SILENT   // ringer mode when device in use
            }
        }
        override fun onDisplayAdded(p0: Int) {}
        override fun onDisplayRemoved(p0: Int) {}
    }

    private fun isDisplayInUse(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (display in displayManager.displays) {
                Log.i(TAG, "isDisplayInUse: state before when = ${display.state}")
                return when (display.state) {
                    STATE_OFF -> false
                    STATE_DOZE -> false
                    STATE_DOZE_SUSPEND -> false
                    else -> {
                        Log.i(TAG, "setupDisplayHardware: DISPLAY STATE (should be on)- ${display.state}")
                        true
                    }
                }
            }
            Log.d(TAG, "isDisplayInUse: No display found")
            return false
        } else {    // if < API20
            Log.i(TAG, "setupDisplayHardware: checking display for API < 20")
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (powerManager.isScreenOn) {
                return true
            }
            return false
        }
    }

    fun startRingman() {
        sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_FASTEST)
    }
    fun stopRingman() {
        sensorManager.unregisterListener(this, proximity)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val distanceInCm = event.values[0]
            Log.i(TAG, "onSensorChanged: proximity is $distanceInCm cm")
            setRingerState(distanceInCm)
        }
    }

    @Synchronized private fun setRingerState(distance: Float) {
        Log.i(TAG, "setRingerState: passed a distance of $distance")
        // shouldn't override DND
        if (notificationManager.currentInterruptionFilter != INTERRUPTION_FILTER_ALL) {
            Log.d(TAG, "setRingerState: interruptFilter is ${notificationManager.currentInterruptionFilter}")
            return
        }

        Log.i(TAG, "setRingerState: audioManagerMode? ${audioManager.mode}")
        Log.i(TAG, "setRingerState: display is on? ${isDisplayInUse()}")
        if (isDisplayInUse() || isCallActive()) {
            Log.i(TAG, "setRingerState: display in use")
            return
        }

        // core business logic
        if (distance <= CM_THRESHOLD && audioManager.ringerMode != RINGER_MODE_VIBRATE)
        {
            Log.i(TAG, "setRingerState (right bef): ${audioManager.ringerMode}")

            audioManager.ringerMode = RINGER_MODE_VIBRATE
            Log.i(TAG, "setRingerState: ${audioManager.ringerMode}")
        }
        else if (distance > CM_THRESHOLD && audioManager.ringerMode != RINGER_MODE_NORMAL)
        {
            Log.i(TAG, "setRingerState (right bef): ${audioManager.ringerMode}")

            audioManager.ringerMode = RINGER_MODE_NORMAL
            Log.i(TAG, "setRingerState: ${audioManager.ringerMode}")
        }
        return
    }

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
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { return }
    override fun onBind(p0: Intent?): IBinder? { return null }
}