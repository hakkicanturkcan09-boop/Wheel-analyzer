package com.wheelanalyzer.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val captureRequest = 4102
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 32)
            setBackgroundColor(0xFFF8FAFC.toInt())
        }
        fun label(text: String, size: Float, color: Int = 0xFF111827.toInt()) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); setPadding(0, 12, 0, 12)
        }
        root.addView(label("WHEEL ANALYZER", 25f))
        root.addView(label("Android 14 • Canlı oyun ekranı analiz prototipi", 14f, 0xFF64748B.toInt()))
        root.addView(label("Bu ilk sürüm ekran yakalama ve kayan panel altyapısını kurar. Yiyecek simgesi tanıma henüz etkin değildir.", 16f))
        val overlayButton = Button(this).apply {
            text = "1 • Kayan pencere iznini ver"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else showStatus("Kayan pencere izni zaten açık.")
            }
        }
        root.addView(overlayButton)
        val startButton = Button(this).apply {
            text = "2 • Ekran takibini başlat"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    showStatus("Önce kayan pencere iznini aç.")
                } else startActivityForResult(projectionManager.createScreenCaptureIntent(), captureRequest)
            }
        }
        root.addView(startButton)
        root.addView(Button(this).apply {
            text = "Takibi durdur"
            setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)); showStatus("Takip durduruldu.") }
        })
        root.addView(label("Gizlilik: Ekran yakalama yalnızca sen başlattığında çalışır. Görüntüler bu prototipte sunucuya gönderilmez.", 13f, 0xFF64748B.toInt()))
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("projectionData", data)
            }
            startForegroundService(service)
            showStatus("Ekran yakalama izni verildi; analiz paneli başlatılıyor.")
        }
    }

    private fun showStatus(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
