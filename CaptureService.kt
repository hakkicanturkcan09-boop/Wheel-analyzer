package com.wheelanalyzer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: TextView? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }
    private var windowManager: WindowManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(7, notification())
        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("projectionData")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, data)
        projection?.registerCallback(projectionCallback, null)
        val metrics = resources.displayMetrics
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay("WheelAnalyzerCapture", metrics.widthPixels, metrics.heightPixels,
            metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null)
        showOverlay()
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "WHEEL ANALYZER\nEkran takibi açık\nSimgeleri tanıma: sonraki geliştirme"
            textSize = 12f; setTextColor(0xFFFFFFFF.toInt()); setPadding(22, 16, 22, 16)
            setBackgroundColor(0xDD111827.toInt())
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 180 }
        windowManager?.addView(view, params)
        overlay = view
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel("wheel_capture", "Wheel Analyzer", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
    private fun notification(): Notification = if (Build.VERSION.SDK_INT >= 26)
        Notification.Builder(this, "wheel_capture").setContentTitle("Wheel Analyzer").setContentText("Ekran takibi çalışıyor").setSmallIcon(android.R.drawable.ic_menu_view).build()
    else Notification.Builder(this).setContentTitle("Wheel Analyzer").setContentText("Ekran takibi çalışıyor").setSmallIcon(android.R.drawable.ic_menu_view).build()

    override fun onDestroy() {
        overlay?.let { windowManager?.removeView(it) }; overlay = null
        display?.release(); display = null
        reader?.close(); reader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop(); projection = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
