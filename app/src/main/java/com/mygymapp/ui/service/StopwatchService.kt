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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat

class StopwatchService : Service() {

    companion object {
        const val CHANNEL_ID = "stopwatch_channel_v2"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.mygymapp.START_STOPWATCH"
        const val ACTION_STOP = "com.mygymapp.STOP_STOPWATCH"

        // 5 light colors readable with black text, one per minute (cycles)
        private val CYCLE_COLORS = intArrayOf(
            0xFFBB86FC.toInt(),  // purple
            0xFFFFD54F.toInt(),  // amber
            0xFF4DD0E1.toInt(),  // cyan
            0xFF81C784.toInt(),  // green
            0xFFF48FB1.toInt(),  // pink
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var startElapsedRealtime = 0L
    private var startWallClockTime = 0L
    private var lastVibrationAt = -1

    private val tickRunnable = object : Runnable {
        override fun run() {
            val elapsed = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000).toInt()
            if (elapsed > 0 && elapsed - lastVibrationAt >= 30) {
                lastVibrationAt = elapsed
                vibrate()
            }
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
                lastVibrationAt = -1
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
                lastVibrationAt = -1
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
        val secs = elapsedSeconds % 60
        val colorIndex = (elapsedSeconds / 60) % CYCLE_COLORS.size
        val bgColor = CYCLE_COLORS[colorIndex]
        val bitmap = createTimerBitmap(secs, bgColor)
        val totalTime = "%d:%02d".format(elapsedSeconds / 60, secs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val n = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(Icon.createWithBitmap(bitmap))
                .setLargeIcon(Icon.createWithBitmap(bitmap))
                .setContentTitle("Stopwatch")
                .setContentText(totalTime)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(bgColor)
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
            .setContentText(totalTime)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(bgColor)
            .setUsesChronometer(true)
            .setWhen(startWallClockTime)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createTimerBitmap(seconds: Int, bgColor: Int): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 12f, 12f, bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 72f
        }

        val cx = size / 2f
        val cy = size / 2f
        val y = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText("%02d".format(seconds), cx, y, textPaint)

        return bitmap
    }

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)
                .defaultVibrator
                .vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
                .vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE))
        }
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
