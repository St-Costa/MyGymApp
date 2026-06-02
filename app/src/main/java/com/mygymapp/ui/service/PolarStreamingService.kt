package com.mygymapp.ui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class PolarStreamingService : Service() {

    companion object {
        // v2 suffix: a channel's importance is fixed once created, so we need a fresh id to
        // downgrade the ongoing notification to IMPORTANCE_MIN on devices that already had the
        // old LOW channel. The legacy channel is deleted in createNotificationChannel().
        const val CHANNEL_ID = "polar_hr_channel_min"
        private const val LEGACY_CHANNEL_ID = "polar_hr_channel"
        const val ALERT_CHANNEL_ID = "polar_hr_alert"
        const val NOTIFICATION_ID = 1002
        const val ALERT_NOTIFICATION_ID = 1003
        private const val ACTION_START = "com.mygymapp.START_POLAR"
        private const val ACTION_STOP = "com.mygymapp.STOP_POLAR"
        private const val ACTION_UPDATE_HR = "com.mygymapp.UPDATE_POLAR_HR"
        private const val ACTION_RECONNECTING = "com.mygymapp.RECONNECTING_POLAR"
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

        /** Switch the ongoing notification to a "Reconnecting…" state (sensor dropped). */
        fun setReconnecting(context: Context) {
            val intent = Intent(context, PolarStreamingService::class.java).apply {
                action = ACTION_RECONNECTING
            }
            context.startService(intent)
        }

        /**
         * Fire a one-shot heads-up notification *with sound* when the sensor disconnects.
         * Independent of the foreground-service notification, on a high-importance channel.
         */
        fun notifyDisconnected(context: Context, deviceName: String?) {
            ensureAlertChannel(context)
            val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Heart rate sensor disconnected")
                .setContentText("${deviceName ?: "Polar H10"} lost connection — reconnecting…")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)
                .notify(ALERT_NOTIFICATION_ID, notification)
        }

        /** Dismiss the disconnect alert (e.g. once reconnected). */
        fun clearDisconnectAlert(context: Context) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(ALERT_NOTIFICATION_ID)
        }

        private fun ensureAlertChannel(context: Context) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Heart Rate Alerts",
                // HIGH → heads-up pop-up with the default notification sound.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts when the Polar sensor disconnects"
                setShowBadge(true)
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private var currentHr: Int? = null
    private var reconnecting: Boolean = false
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
                reconnecting = false
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
                    reconnecting = false
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification())
                }
            }
            ACTION_RECONNECTING -> {
                reconnecting = true
                currentHr = null
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification())
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

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val title = when {
            reconnecting -> "Reconnecting…"
            currentHr != null -> "${currentHr} BPM"
            else -> "Connecting…"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(deviceName)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // PRIORITY_MIN keeps the icon out of the status bar (collapsed in the shade).
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heart Rate Monitor",
            // MIN → no status-bar icon, collapsed at the bottom of the shade, silent.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Polar H10 heart rate streaming"
            setShowBadge(false)
            setSound(null, null)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        nm.createNotificationChannel(channel)
    }
}
