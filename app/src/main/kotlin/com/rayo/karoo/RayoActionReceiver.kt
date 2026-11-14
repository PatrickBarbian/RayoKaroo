package com.rayo.karoo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class RayoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val ext = RayoExtension.instance

        if (ext != null) {
            when (action) {
                RayoExtension.ACTION_TOGGLE_LIGHT -> ext.toggleLight()
                RayoExtension.ACTION_CYCLE_PROFILE -> ext.cycleProfile()
            }
        } else if (context != null) {
            Log.d("RayoActionReceiver", "RayoExtension.instance is null; starting service for action: $action")
            val serviceIntent = Intent(context, RayoExtension::class.java).apply {
                this.action = action
            }
            try {
                context.startService(serviceIntent)
            } catch (e: Exception) {
                Log.e("RayoActionReceiver", "Failed to start RayoExtension from BroadcastReceiver", e)
            }
        }
    }
}
