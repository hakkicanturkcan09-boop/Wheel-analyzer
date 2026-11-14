package com.wheelanalyzer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: TextView? = null
    private var windowManager: WindowManager? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    private var frameCount = 0

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                7,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(7, notification())
        }

        val code = intent?.getIntExtra("resultCode", 0) ?: 0

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(
                "projectionData",
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("projectionData")
        }

        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(code, data)

            projection?.registerCallback(
                projectionCallback,
                mainHandler
            )

            val metrics = resources.displayMetrics

            captureThread = HandlerThread("WheelCaptureThread").apply {
                start()
            }

            captureHandler = Handler(captureThread!!.looper)

            reader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                2
            )

            reader?.setOnImageAvailableListener({ imageReader ->
                val image = imageReader.acquireLatestImage()

                if (image != null) {
                    try {
                        frameCount++

                        if (frameCount == 1 || frameCount % 15 == 0) {
                            mainHandler.post {
                                updateOverlay()
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
            }, captureHandler)

            display = projection?.createVirtualDisplay(
                "WheelAnalyzerCapture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                captureHandler
            )

            showOverlay()

            START_NOT_STICKY

        } catch (e: Exception) {
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun showOverlay() {
        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val view = TextView(this).apply {
            text = "WHEEL ANALYZER\nGörüntü bekleniyor..."
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 16, 20, 16)
            setBackgroundColor(0xEE111827.toInt())
        }

        val type =
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 180
        }

        windowManager?.addView(view, params)
        overlay = view

        updateOverlay()
    }

    private fun updateOverlay() {
        overlay?.text =
            "WHEEL ANALYZER\n" +
            "Ekran yakalama: AKTİF\n" +
            "Alınan görüntü: $frameCount\n" +
            "Sembol tanıma: Hazırlanıyor"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "wheel_capture",
                "Wheel Analyzer",
                NotificationManager.IMPORTANCE_LOW
            )

            (getSystemService(NOTIFICATION_SERVICE)
                as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "wheel_capture")
                .setContentTitle("Wheel Analyzer")
                .setContentText("Ekran takibi çalışıyor")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Wheel Analyzer")
                .setContentText("Ekran takibi çalışıyor")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
    }

    override fun onDestroy() {
        reader?.setOnImageAvailableListener(null, null)

        overlay?.let {
            windowManager?.removeView(it)
        }
        overlay = null

        display?.release()
        display = null

        reader?.close()
        reader = null

        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        mainHandler.removeCallbacksAndMessages(null)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
