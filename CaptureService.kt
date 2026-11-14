package com.wheelanalyzer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.content.pm.ServiceInfo
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: TextView? = null
    private var windowManager: WindowManager? = null

    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private var lastFrameTime = 0L
    private lateinit var sampleCollector: SlotSampleCollector

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

        if (data == null || projection != null) {
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }

        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        projection = manager.getMediaProjection(code, data)

        projection?.registerCallback(
            projectionCallback,
            Handler(mainLooper)
        )

        startImageThread()
        sampleCollector = SlotSampleCollector()
        startCapture()
        showOverlay()

        return START_NOT_STICKY
    }

    private fun startImageThread() {
        imageThread = HandlerThread("WheelImageProcessor")
        imageThread?.start()
        imageHandler = Handler(imageThread!!.looper)
    }

    private fun startCapture() {

        val metrics = resources.displayMetrics

        reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            3
        )

        reader?.setOnImageAvailableListener(
            { imageReader ->
                val image = imageReader.acquireLatestImage()
                    ?: return@setOnImageAvailableListener

                try {
                    val now = SystemClock.elapsedRealtime()

                    if (now - lastFrameTime >= 500) {
                        lastFrameTime = now
                        processFrame(image)
                    }

                } catch (e: Exception) {
                    Log.e("WheelAnalyzer", "Görüntü hatası", e)
                } finally {
                    image.close()
                }
            },
            imageHandler
        )

        display = projection?.createVirtualDisplay(
            "WheelAnalyzerCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            imageHandler
        )
    }

    private fun processFrame(image: Image) {

        val plane = image.planes[0]
        val buffer = plane.buffer

        val width = image.width
        val height = image.height

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val rowPadding =
            (rowStride - pixelStride * width) / pixelStride

        val bitmapWidth = width + rowPadding

        val fullBitmap = Bitmap.createBitmap(
            bitmapWidth,
            height,
            Bitmap.Config.ARGB_8888
        )

        try {
            buffer.rewind()
            fullBitmap.copyPixelsFromBuffer(buffer)

            // Sabit geçmiş sonuçları bölgesi.
            val left = (width * 0.28f).toInt()
            val top = (height * 0.84f).toInt()
            val right = (width * 0.94f).toInt()
            val bottom = (height * 0.90f).toInt()

            val roiWidth = right - left
            val roiHeight = bottom - top

            if (roiWidth <= 0 || roiHeight <= 0) return

            val historyBitmap = Bitmap.createBitmap(
                fullBitmap,
                left,
                top,
                roiWidth,
                roiHeight
            )

            try {
                val slotWidth = roiWidth / 6

                for (i in 0 until 6) {

                    val slotLeft = i * slotWidth

                    val currentWidth =
                        if (i == 5) roiWidth - slotLeft
                        else slotWidth

                    val slotBitmap = Bitmap.createBitmap(
                        historyBitmap,
                        slotLeft,
                        0,
                        currentWidth,
                        roiHeight
                    )

                    try {
                        sampleCollector.collect(slotBitmap)
                        Log.d(
                            "WheelAnalyzer",
                            "Slot $i görüntüsü alındı: " +
                                    "${slotBitmap.width}x${slotBitmap.height}"
                        )

                        // Sonraki aşamada sembol tanıma
                        // modeli burada çalıştırılacak.

                    } finally {
                        slotBitmap.recycle()
                    }
                }

            } finally {
                historyBitmap.recycle()
            }

        } finally {
            fullBitmap.recycle()
        }
    }

    private fun showOverlay() {

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val view = TextView(this).apply {
            text = "WHEEL ANALYZER\nEkran takibi açık\n6 bölge okunuyor"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(22, 16, 22, 16)
            setBackgroundColor(0xDD111827.toInt())
        }

        val type =
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

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

        overlay?.let {
            windowManager?.removeView(it)
        }
        overlay = null

        display?.release()
        display = null

        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null

        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null

        imageThread?.quitSafely()
        imageThread = null
        imageHandler = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
