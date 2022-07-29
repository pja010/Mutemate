package com.example.ringman

import android.content.Context
import android.content.Context.POWER_SERVICE
import android.os.PowerManager
import android.util.Log

class ProximityManager(context: Context) {
    private val powerManager: PowerManager = context.getSystemService(POWER_SERVICE) as PowerManager
    private val wakeLock: PowerManager.WakeLock = powerManager.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
//        PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY,
        "lock:proximity_screen_off")

    fun acquire() {
        if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            if (wakeLock.isHeld) {
                Log.i(TAG, "acquire: ProximitySensor inaccessible at the moment")
                wakeLock.release()
            }
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.i(TAG, "acquire: ProximitySensor locked in")
        } else {
            Log.w(TAG, "not supported")
        }
    }

    fun release() {
        if (wakeLock.isHeld)
            wakeLock.release()
        Log.i(TAG,  "release: Proximity sensor released")
    }

    companion object {
        private const val TAG = "ProximitySensor"
        private const val WAKE_LOCK_TIMEOUT_MS: Long = 2 * 3600 * 1000
    }
}