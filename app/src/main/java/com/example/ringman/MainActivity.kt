package com.example.ringman

import android.app.NotificationManager
import android.content.ContentValues.TAG
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.widget.SwitchCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()

        // Grant access to override ringer state.
        val notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            startActivity(intent)
        }

        // Default UI switch to off before user enables service.
        val btn = findViewById<SwitchCompat>(R.id.switch_btn)
        Log.d(TAG, "onCreate: Service status - ${getServiceState(this)}")
        btn.isChecked = getServiceState(this) == ServiceState.ENABLED
        btn.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Log.i(TAG, "onCreate: Start service")
                setServiceMode(Commands.ENABLE)
            }
            else {
                Log.i(TAG, "onCreate: Stop service")
                setServiceMode(Commands.DISABLE)
            }
        }
    }

    private fun setServiceMode(command: Commands) {
        if (getServiceState(this) == ServiceState.DISABLED
            && command == Commands.DISABLE
        ) {
            return
        }
        Intent(this, RingmanService::class.java).also {
            it.action = command.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it)
                return
            }
            startService(it)
        }
    }

}