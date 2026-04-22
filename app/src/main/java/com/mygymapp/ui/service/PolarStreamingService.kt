package com.mygymapp.ui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class PolarStreamingService : Service() {

    companion object {
        const val CHANNEL_ID = "polar_hr_channel"
        const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.mygymapp.START_POLAR"
        private const val ACTION_STOP = "com.mygymapp.STOP_POLAR"
        private const val ACTION_UPDATE_HR = "com.mygymapp.UPDATE_POLAR_HR"
        private const val EXTRA_HR = "hr"
        private const val EXTRA_DEVICE = "device"

        fun start(context: Context, deviceName: String?) {
            val intent = Intent(context, PolarStreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE, deviceName ?: "Polar H10")
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PolarStreamingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateHr(context: Context, hr: Int) {
            val intent = Intent(context, PolarStreamingService::class.java).apply {
                action = ACTION_UPDATE_HR
                putExtra(EXTRA_HR, hr)
            }
            context.startService(intent)
        }
    }

    private var currentHr: Int? = null
    private var deviceName: String = "Polar H10"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                deviceName = intent.getStringExtra(EXTRA_DEVICE) ?: "Polar H10"
                currentHr = null
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
            ACTION_UPDATE_HR -> {
                val hr = intent.getIntExtra(EXTRA_HR, 0)
                if (hr > 0) {
                    currentHr = hr
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification())
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // START_STICKY so the OS re-creates the service if it kills it under
        // memory pressure (the app reconnects the Polar on restart).
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val hrText = currentHr?.let { "$it BPM" } ?: "Connecting..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(hrText)
            .setContentText(deviceName)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heart Rate Monitor",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Polar H10 heart rate streaming"
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
