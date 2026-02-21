package com.mygymapp.ui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat

class StopwatchService : Service() {

    companion object {
        const val CHANNEL_ID = "stopwatch_channel_v2"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.mygymapp.START_STOPWATCH"
        const val ACTION_STOP = "com.mygymapp.STOP_STOPWATCH"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startElapsedRealtime = 0L
    private var startWallClockTime = 0L  // for chronometer (stays constant)

    private val tickRunnable = object : Runnable {
        override fun run() {
            val elapsed = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000).toInt()
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(elapsed))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startElapsedRealtime = SystemClock.elapsedRealtime()
                startWallClockTime = System.currentTimeMillis()
                val notification = buildNotification(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                handler.postDelayed(tickRunnable, 1000)
            }
            ACTION_STOP -> {
                handler.removeCallbacks(tickRunnable)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(elapsedSeconds: Int): Notification {
        val minutes = elapsedSeconds / 60
        val secs = elapsedSeconds % 60
        val bitmap = createTimerBitmap(minutes, secs)

        // Android 16+ (API 36): use native Notification.Builder + set requestPromotedOngoing
        // extra via bundle (setRequestPromotedOngoing() is only in API 36.1+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val n = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(Icon.createWithBitmap(bitmap))
                .setLargeIcon(Icon.createWithBitmap(bitmap))
                .setContentTitle("Stopwatch")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(0xFFBB86FC.toInt())
                .setUsesChronometer(true)
                .setWhen(startWallClockTime)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
            n.extras.putBoolean("android.requestPromotedOngoing", true)
            return n
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(IconCompat.createWithBitmap(bitmap))
            .setLargeIcon(bitmap)
            .setContentTitle("Stopwatch")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFFBB86FC.toInt())
            .setUsesChronometer(true)
            .setWhen(startWallClockTime)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createTimerBitmap(minutes: Int, seconds: Int): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Purple background (Primary: 0xFFBB86FC)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBB86FC.toInt()
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 12f, 12f, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 50f
        }

        val cx = size / 2f
        val half = size / 2f
        val vOffset = -(textPaint.ascent() + textPaint.descent()) / 2f

        // Minutes on top half
        canvas.drawText(minutes.toString(), cx, half / 2f + vOffset, textPaint)

        // Thin divider
        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            alpha = 60
            strokeWidth = 1f
        }
        canvas.drawLine(10f, half, size - 10f, half, divPaint)

        // Seconds on bottom half (always 2 digits)
        canvas.drawText("%02d".format(seconds), cx, half + half / 2f + vOffset, textPaint)

        return bitmap
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stopwatch",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Stretch timer stopwatch"
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
