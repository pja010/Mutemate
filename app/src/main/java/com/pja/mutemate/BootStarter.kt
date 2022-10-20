package com.pja.mutemate

import android.content.BroadcastReceiver
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootStarter: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && getServiceState(context) == ServiceState.ENABLED) {
            Intent(context, MuteService::class.java).also {
                it.action = Commands.ENABLE.name
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.i(TAG, "Boot-starting service in >= 26 Mode")
                    context.startForegroundService(it)
                    return
                }
                Log.i(TAG, "Boot-starting service in < 26 Mode on boot")
                context.startService(it)
            }
        }
    }
}