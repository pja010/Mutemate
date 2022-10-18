/*
Mutemate automatically sets your phone into vibrate mode when you put it in your pocket. 
    Copyright (C) 2022  Per Astrom

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
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

private const val CM_THRESHOLD = 1f
private const val NANOS_THRESHOLD = 500000000

class RingmanService: Service() , SensorEventListener {
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false
    private lateinit var audioManager: AudioManager
    private lateinit var sensorManager: SensorManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var displayManager: DisplayManager
    private var proximity: Sensor? = null
    private var timeOfLastSensorUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: service created")
        val notification = createNotification()
        startForeground(1, notification)
        setupSensorHardware()
    }

    private fun setupSensorHardware() {
        // Set up ringer for use.
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Proximity sensor access.
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        if (sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null) {
            proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        } else {
            Log.e(TAG, "setupSensorHardware: proximity-sensor not found")
        }

        // Device display state.
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: executed with startId: $startId")
        if (intent != null) {
            when (intent.action) {
                Commands.ENABLE.name -> startService()
                Commands.DISABLE.name -> stopService()
            }
        } else {
            Log.e(TAG, "onStartCommand: null intent")
        }
        return START_STICKY
    }

    private fun startService() {
        if (isServiceStarted) return
        Log.i(TAG, "startService: starting foreground service")
        Toast.makeText(this, "Automute enabled", Toast.LENGTH_SHORT).show()
        isServiceStarted = true
        saveServiceState(this, ServiceState.ENABLED)

        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EndlessService::lock").apply {
                    acquire()
                }
            }

        if (isServiceStarted) {
            displayManager.registerDisplayListener(displayListener, null)
            Log.i(TAG, "setupDisplay: listener registered")
        }
    }

    private fun stopService() {
        Log.i(TAG, "stopService: stopping")
        Toast.makeText(this, "Automute disabled", Toast.LENGTH_SHORT).show()
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
        saveServiceState(this, ServiceState.DISABLED)
        stopAutomute()
        displayManager.unregisterDisplayListener(displayListener)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            stopAutomute()
            Log.i(TAG, "onDisplayChanged: displayId - $displayId")
            if (!isDisplayInUse()) {
                startAutomute()
            } else {
                stopAutomute()
            }
        }
        override fun onDisplayAdded(displayId: Int) {
            Log.i(TAG, "onDisplayAdded: displayId - $displayId")
        }
        override fun onDisplayRemoved(displayId: Int) {
            Log.i(TAG, "onDisplayRemoved: displayId - $displayId")
        }
    }

    private fun isDisplayInUse(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isInteractive
    }

    fun startAutomute() {
        proximity?.also { proximity ->
            sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL)
        }
        Log.i(TAG, "startAutomute: ${proximity?.maximumRange}")
    }
    fun stopAutomute() {
        sensorManager.unregisterListener(this, proximity)
    }

    override fun onSensorChanged(event: SensorEvent) { // TODO - could add delay to avoid unintended switches
        val actualTimeInNanoSec = event.timestamp
        Log.i(TAG, "onSensorChanged: time now - $actualTimeInNanoSec")
        Log.i(TAG, "onSensorChanged: time of last update- $timeOfLastSensorUpdate")
        if(actualTimeInNanoSec - timeOfLastSensorUpdate < NANOS_THRESHOLD) {
            return
        }
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val proximityInCm = event.values[0]
            Log.d(TAG, "onSensorChanged: proximity is $proximityInCm cm")
            val isInPocket = isDeviceInPocket(proximityInCm)
            setRingerState(isInPocket)
        }
        timeOfLastSensorUpdate = actualTimeInNanoSec
    }
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        Log.d(TAG, "onAccuracyChanged: sensor=$sensor, accuracy=$accuracy")
        return
    }

    @Synchronized private fun setRingerState(isInPocket: Boolean) {

        // System should not override Do-Not-Disturb mode.
        if (notificationManager.currentInterruptionFilter != INTERRUPTION_FILTER_ALL) {
            Log.d(TAG, "setRingerState: interruptFilter is ${notificationManager.currentInterruptionFilter}")
            return
        }

        if (isDisplayInUse() || isCallActive()) {
            Log.d(TAG, "setRingerState: display in use")
            return
        }

        // Core application logic.
        if (isInPocket && audioManager.ringerMode != RINGER_MODE_VIBRATE) {
            audioManager.ringerMode = RINGER_MODE_VIBRATE
        } else if (!isInPocket && audioManager.ringerMode != RINGER_MODE_NORMAL) {
            audioManager.ringerMode = RINGER_MODE_NORMAL
        }
        return
    }

    private fun isDeviceInPocket(proximityInCm: Float): Boolean {
        return proximityInCm <= CM_THRESHOLD
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
        stopAutomute()
        displayManager.unregisterDisplayListener(displayListener)
        Log.i(TAG, "onDestroy: service destroyed")
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "Automute_SERVICE_CHANNEL"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                notificationChannelId,
                "Automute-notifications-channel",
                NotificationManager.IMPORTANCE_LOW
            ).let {
                it.description = "Automute-service-channel"
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
            .setContentTitle("Mutemate")
            .setContentText("Mutemate activated")
            .setContentIntent(pendingIntent)
            .setTicker("Mutemate activated")
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setPriority(Notification.PRIORITY_LOW) // Compatibility with < android 26.
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

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

}
