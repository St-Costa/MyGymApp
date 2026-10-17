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
        const val CHANNEL_ID = "stopwatch_channel_v3"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.mygymapp.START_STOPWATCH"
        const val ACTION_STOP = "com.mygymapp.STOP_STOPWATCH"
        /**
         * Optional start extra: when present and > 0 the service runs a countdown loop of
         * that many seconds (stretch mode) instead of the legacy count-up stopwatch —
         * restarting automatically at zero, buzzing every 30s inside the loop and three
         * times fast on each loop completion. Both current callers (standalone stretch
         * and superset, which runs it off the chain's first stretch set) send it; absent =
         * plain count-up, kept as a fallback.
         */
        const val EXTRA_DURATION_SECONDS = "com.mygymapp.COUNTDOWN_DURATION_SECONDS"

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
    private var lastVibrationAt = 0
    // Countdown-loop mode (stretch): null = legacy count-up stopwatch.
    private var countdownDuration: Int? = null
    private var loopStartElapsed = 0L
    private var loopCount = 1

    // Reused across every tick to avoid per-second allocations during a stretch workout.
    private val timerBitmap: Bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    private val timerCanvas = Canvas(timerBitmap)
    private val timerBgRect = RectF(0f, 0f, 96f, 96f)
    private val timerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 72f
    }
    private val timerTextY = 96f / 2f - (timerTextPaint.ascent() + timerTextPaint.descent()) / 2f

    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val duration = countdownDuration
            if (duration != null) {
                // Countdown-loop mode: the 30s cadence is per-loop and the loop-end
                // triple buzz always wins (see StopwatchBuzzSchedule.countdown).
                var loopElapsed = ((now - loopStartElapsed) / 1000).toInt()
                when (StopwatchBuzzSchedule.countdown(loopElapsed, duration, lastVibrationAt)) {
                    StopwatchBuzz.LOOP_END -> {
                        vibrateTriple()
                        loopStartElapsed = now
                        loopElapsed = 0
                        loopCount++
                        lastVibrationAt = 0
                    }
                    StopwatchBuzz.PERIODIC -> {
                        lastVibrationAt = loopElapsed
                        vibrate()
                    }
                    StopwatchBuzz.NONE -> Unit
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildCountdownNotification(duration - loopElapsed, loopCount))
            } else {
                val elapsed = ((now - startElapsedRealtime) / 1000).toInt()
                if (StopwatchBuzzSchedule.countUp(elapsed, lastVibrationAt)) {
                    lastVibrationAt = elapsed
                    vibrate()
                }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(elapsed))
            }
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
                // Idempotent: clear any previously scheduled tick before restarting
                handler.removeCallbacks(tickRunnable)
                startElapsedRealtime = SystemClock.elapsedRealtime()
                startWallClockTime = System.currentTimeMillis()
                lastVibrationAt = 0
                countdownDuration = intent.getIntExtra(EXTRA_DURATION_SECONDS, 0).takeIf { it > 0 }
                loopStartElapsed = startElapsedRealtime
                loopCount = 1
                val notification = countdownDuration?.let { buildCountdownNotification(it, 1) }
                    ?: buildNotification(0)
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
                lastVibrationAt = 0
                countdownDuration = null
                loopCount = 1
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
        return buildTimerNotification(
            title = "Stopwatch",
            text = "%d:%02d".format(elapsedSeconds / 60, secs),
            secsForIcon = secs,
            bgColor = CYCLE_COLORS[colorIndex],
        )
    }

    private fun buildCountdownNotification(remainingSeconds: Int, loop: Int): Notification {
        val secs = remainingSeconds % 60
        val colorIndex = (remainingSeconds / 60) % CYCLE_COLORS.size
        return buildTimerNotification(
            title = "Stretch",
            text = "%d:%02d · loop %d".format(remainingSeconds / 60, secs, loop),
            secsForIcon = secs,
            bgColor = CYCLE_COLORS[colorIndex],
        )
    }

    private fun buildTimerNotification(
        title: String,
        text: String,
        secsForIcon: Int,
        bgColor: Int,
    ): Notification {
        val bitmap = createTimerBitmap(secsForIcon, bgColor)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val n = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(Icon.createWithBitmap(bitmap))
                .setLargeIcon(Icon.createWithBitmap(bitmap))
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_STOPWATCH)
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
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
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
        timerBgPaint.color = bgColor
        timerCanvas.drawRoundRect(timerBgRect, 12f, 12f, timerBgPaint)
        timerCanvas.drawText("%02d".format(seconds), 48f, timerTextY, timerTextPaint)
        return timerBitmap
    }

    private fun systemVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }

    private fun vibrate() {
        systemVibrator()?.vibrate(VibrationEffect.createOneShot(2000, 255))
    }

    /**
     * Three quick buzzes marking a completed countdown loop. A single waveform rather
     * than three one-shots: one `vibrate()` call can't be interleaved by the next tick.
     */
    private fun vibrateTriple() {
        try {
            val vibrator = systemVibrator() ?: return
            // Cut any periodic buzz still running so only the three short ones are felt.
            vibrator.cancel()
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 150, 150, 150, 150, 150), -1)
            )
        } catch (_: Throwable) {
            // No-op: haptics must never break the timer.
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stopwatch",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Stretch timer stopwatch"
            setShowBadge(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
