package com.legendaai.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
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
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: TextView? = null
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
            .setContentText("Procurando legendas escritas na imagem do vídeo")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        density = metrics.densityDpi

        workerThread = HandlerThread("LegendaAI-OCR").also { it.start() }
        worker = Handler(workerThread!!.looper)
        val options = TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.PORTUGUESE).build()
        translator = Translation.getClient(options)
        showOverlay("LegendaAI ligado — aguardando legenda...")

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < 850 || !processing.compareAndSet(false, true)) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameAt = now
            val image = source.acquireLatestImage()
            if (image == null) { processing.set(false); return@setOnImageAvailableListener }
            worker?.post { processImage(image) }
        }, worker)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopCapture(); stopSelf() }
        }, mainHandler)
        virtualDisplay = projection?.createVirtualDisplay("LegendaAI-screen", screenWidth, screenHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, worker)
    }

    private fun processImage(image: Image) {
        try {
            val bitmap = imageToBitmap(image) ?: return
            val input = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { it.lines }
                        .filter { line -> line.boundingBox?.centerY()?.let { it > screenHeight * 0.70 } == true }
                        .sortedBy { it.boundingBox?.top ?: 0 }
                    val text = lines.joinToString(" ") { it.text }.trim()
                    if (text.length >= 3 && text != lastText && text != lastTranslated) {
                        lastText = text
                        translate(text)
                    }
                }
                .addOnFailureListener { }
                .addOnCompleteListener { bitmap.recycle(); image.close(); processing.set(false) }
        } catch (_: Exception) {
            image.close(); processing.set(false)
        }
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
            .addOnSuccessListener { translated -> lastTranslated = translated; showOverlay(translated) }
            .addOnFailureListener { showOverlay("Não foi possível traduzir esta legenda") }
    }

    private fun showOverlay(text: String) {
        mainHandler.post {
            val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { windowManager = it }
            val view = overlay ?: TextView(this).also { tv ->
                tv.setTextColor(android.graphics.Color.WHITE)
                tv.textSize = 19f
                tv.gravity = Gravity.CENTER
                tv.setPadding(24, 14, 24, 14)
                tv.setBackgroundColor(0xDD17151D.toInt())
                val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
                val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT)
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.y = (screenHeight * 0.30f).toInt()
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
        }
        translator?.close(); translator = null
        workerThread?.quitSafely(); workerThread = null; worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() { stopCapture(); super.onDestroy() }
}
