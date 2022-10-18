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
