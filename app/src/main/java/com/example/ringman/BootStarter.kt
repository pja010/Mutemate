package com.example.ringman

import android.content.BroadcastReceiver
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootStarter: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && getServiceState(context) == ServiceState.ENABLED) {
            Intent(context, RingmanService::class.java).also {
                it.action = Actions.ENABLE.name
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.i(TAG, "onReceive: Starting service in >= 26 Mode on boot")
                    context.startForegroundService(it)
                    return
                }
                Log.i(TAG, "onReceive: Starting service in < 26 Mode on boot")
                context.startService(it)
            }
        }
    }
}