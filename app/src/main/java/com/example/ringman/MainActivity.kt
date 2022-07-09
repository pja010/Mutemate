package com.example.ringman

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // grant access to override ringer state
        val notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && !notificationManager.isNotificationPolicyAccessGranted
        ) {
            val intent = Intent(
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            )
            startActivity(intent)
        }

        // if button enabled, start persistent background work
        findViewById<Button>(R.id.btnStartService).let {
            it.setOnClickListener {
                Log.i(ContentValues.TAG, "onCreate: Start service")
                actionOnService(Actions.ENABLE)
            }
        }
        findViewById<Button>(R.id.btnStopService).let {
            it.setOnClickListener {
                Log.i(ContentValues.TAG, "onCreate: Stop service")
                actionOnService(Actions.DISABLE)
            }
        }
// todo default switch to off before user interaction

//        val btn = findViewById<SwitchCompat>(R.id.switch_btn)
//        btn.setOnCheckedChangeListener { _, isChecked ->
//            if (isChecked) {
//                Log.i(ContentValues.TAG, "onCreate: Start service")
//                actionOnService(Actions.ENABLE)
//            }
//            else {
//                Log.i(ContentValues.TAG, "onCreate: Stop service")
//                actionOnService(Actions.DISABLE)
//            }
//        }
    }

    private fun actionOnService(action: Actions) {
        if (getServiceState(this) == ServiceState.DISABLED && action == Actions.DISABLE) return
        Intent(this, RingmanService::class.java).also {
            it.action = action.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it)
                return
            }
            startService(it)
        }
    }

}