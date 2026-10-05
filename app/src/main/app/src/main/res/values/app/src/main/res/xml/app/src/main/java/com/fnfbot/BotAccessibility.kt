package com.fnfbot

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    private val lastTap = LongArray(4)
    private val noteX = FloatArray(4)
    private val tapX = FloatArray(4)
    private var lineY = 0.2f
    private var realW = 1f
    private var realH = 1f

    // цвета нот: влево, вниз, вверх, вправо
    private val colors = arrayOf(
        intArrayOf(194, 75, 153),
        intArrayOf(0, 255, 255),
        intArrayOf(18, 250, 5),
        intArrayOf(249, 57, 63)
    )
    private val tolerance = 100

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bot", "Bot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bot")
            .setContentTitle("FNF бот работает")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)

        for (i in 0..3) {
            noteX[i] = Cfg.get(this, i)
            tapX[i] = Cfg.get(this, 5 + i)
        }
        lineY = Cfg.get(this, 4)

        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        realW = max(dm.widthPixels, dm.heightPixels).toFloat()
        realH = min(dm.widthPixels, dm.heightPixels).toFloat()
        val cw = (realW / 2).toInt()
        val ch = (realH / 2).toInt()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, null)

        thread = HandlerThread("cap").also { it.start() }
        val handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay(
            "fnf", cw, ch, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage()
            if (img != null) {
                try { process(img) } finally { img.close() }
            }
        }, handler)
        return START_NOT_STICKY
    }

    private fun process(img: Image) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val now = SystemClock.uptimeMillis()
        val y = (lineY * img.height).toInt().coerceIn(0, img.height - 1)
        val taps = ArrayList<Pair<Float, Float>>()
        for (i in 0..3) {
            val x = (noteX[i] * img.width).toInt().coerceIn(0, img.width - 1)
            val o = y * rs + x * ps
            if (o + 2 >= buf.limit()) continue
            val r = buf.get(o).toInt() and 0xFF
            val g = buf.get(o + 1).toInt() and 0xFF
            val b = buf.get(o + 2).toInt() and 0xFF
            val c = colors[i]
            val d = abs(r - c[0]) + abs(g - c[1]) + abs(b - c[2])
            if (d < tolerance && now - lastTap[i] > 90) {
                lastTap[i] = now
                taps.add(Pair(tapX[i] * realW, realH * 0.5f))
            }
        }
        if (taps.isNotEmpty()) BotAccessibility.instance?.tap(taps)
    }

    override fun onDestroy() {
        display?.release()
        reader?.close()
        projection?.stop()
        thread?.quitSafely()
        super.onDestroy()
    }
}
