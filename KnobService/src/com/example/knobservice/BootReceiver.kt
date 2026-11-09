package com.example.knobservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (UserHandle.myUserId() != 0) {
            Log.d("KnobService", "BootReceiver: Ignoriere für nicht-System User ${UserHandle.myUserId()}")
            return
        }
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.d("KnobService", "Boot completed, Starte KnobService...")
            val serviceIntent = Intent(context, KnobService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}