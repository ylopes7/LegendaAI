package com.legendaai.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenTranslatorService : Service() {
    companion object {
        const val ACTION_START = "com.legendaai.START"
        const val ACTION_STOP = "com.legendaai.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "legendaai_capture"
        private const val NOTIFICATION_ID = 42
        private const val PREFS = "overlay_position"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var translator: Translator? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private val processing = AtomicBoolean(false)
    @Volatile private var lastText = ""
    @Volatile private var lastTranslated = ""
    @Volatile private var lastFrameAt = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var viewStartX = 0
    private var viewStartY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopCapture(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data != null) startCapture(code, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("LegendaAI ativo")
            .setContentText("Analisando a região de legendas do vídeo")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        density = metrics.densityDpi

        workerThread = HandlerThread("LegendaAI-OCR").also { it.start() }
        worker = Handler(workerThread!!.looper)
        translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.PORTUGUESE).build())
        showOverlay("LegendaAI • aguardando legenda")

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < 700 || !processing.compareAndSet(false, true)) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameAt = now
            val frame = source.acquireLatestImage()
            if (frame == null) { processing.set(false); return@setOnImageAvailableListener }
            worker?.post { processImage(frame) } ?: run { frame.close(); processing.set(false) }
        }, worker)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture(); stopSelf() }
        }, mainHandler)
        virtualDisplay = projection?.createVirtualDisplay("LegendaAI-screen", screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, worker)
    }

    private fun processImage(image: Image) {
        var bitmap: Bitmap? = null
        try {
            bitmap = imageToBitmap(image)
            if (bitmap == null) { image.close(); processing.set(false); return }
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    // Prioriza linhas centralizadas na zona habitual das legendas, evitando barras e menus.
                    val candidates = result.textBlocks.flatMap { it.lines }
                        .filter { line ->
                            val box = line.boundingBox ?: return@filter false
                            val cx = box.centerX().toFloat()
                            val cy = box.centerY().toFloat()
                            val widthRatio = box.width().toFloat() / screenWidth.coerceAtLeast(1)
                            val value = line.text.trim()
                            cx in (screenWidth * 0.12f)..(screenWidth * 0.88f) &&
                                cy in (screenHeight * 0.52f)..(screenHeight * 0.90f) &&
                                widthRatio >= 0.12f && value.length >= 4 &&
                                value != lastTranslated && !looksLikePlayerControl(value)
                        }
                        .sortedBy { it.boundingBox?.top ?: 0 }
                        .takeLast(3)
                    val text = candidates.joinToString(" ") { it.text.trim() }
                        .replace(Regex("\\s+"), " ").trim()
                    if (text.length >= 4 && text != lastText && text != lastTranslated) {
                        lastText = text
                        translate(text)
                    }
                }
                .addOnCompleteListener {
                    bitmap?.recycle()
                    image.close()
                    processing.set(false)
                }
        } catch (_: Exception) {
            bitmap?.recycle()
            image.close()
            processing.set(false)
        }
    }

    private fun looksLikePlayerControl(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
        val controls = listOf("pause", "play", "settings", "next", "previous", "subscribe", "skip ads", "volume", "autoplay")
        return normalized in controls
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun translate(text: String) {
        val activeTranslator = translator ?: return
        activeTranslator.downloadModelIfNeeded()
            .onSuccessTask { activeTranslator.translate(text) }
            .addOnSuccessListener { translated ->
                lastTranslated = translated
                showOverlay(translated)
            }
            .addOnFailureListener { showOverlay("Falha ao traduzir. Verifique a conexão.") }
    }

    private fun showOverlay(text: String) {
        mainHandler.post {
            val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { windowManager = it }
            val view = overlay ?: TextView(this).also { tv ->
                tv.setTextColor(Color.WHITE)
                tv.textSize = 20f
                tv.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                tv.gravity = Gravity.CENTER
                tv.setPadding(18, 10, 18, 10)
                tv.setShadowLayer(7f, 0f, 2f, Color.BLACK)
                tv.background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 16f * resources.displayMetrics.density
                }
                tv.maxWidth = (screenWidth - 32 * resources.displayMetrics.density).toInt()
                tv.setOnTouchListener { _, event ->
                    val params = overlayParams ?: return@setOnTouchListener false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            dragStartX = event.rawX; dragStartY = event.rawY
                            viewStartX = params.x; viewStartY = params.y
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = viewStartX + (event.rawX - dragStartX).toInt()
                            params.y = viewStartY + (event.rawY - dragStartY).toInt()
                            try { wm.updateViewLayout(tv, params) } catch (_: Exception) {}
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putInt("x", params.x).putInt("y", params.y).apply()
                            true
                        }
                        else -> true
                    }
                }
                val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT)
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.x = prefs.getInt("x", 0)
                params.y = prefs.getInt("y", (screenHeight * 0.68f).toInt())
                overlayParams = params
                wm.addView(tv, params)
                overlay = tv
            }
            view.text = text
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Captura de tela LegendaAI", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun stopCapture() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        mainHandler.post {
            try { overlay?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            overlay = null
            overlayParams = null
        }
        translator?.close(); translator = null
        workerThread?.quitSafely(); workerThread = null; worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() { stopCapture(); super.onDestroy() }
}