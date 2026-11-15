package com.example.android.media

import android.app.Activity
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
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.example.domain.model.MediaProjectionConsentState
import com.example.domain.model.ScreenshotCaptureOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class MediaProjectionSessionSnapshot(
    val consentState: MediaProjectionConsentState = MediaProjectionConsentState.NOT_GRANTED,
    val statusReason: String = "Screen capture permission not granted. Tap Grant Screen Capture when needed.",
    val isSessionActive: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val lastCapturePath: String? = null,
    val lastCaptureTimestampMillis: Long? = null
)

interface ScreenshotCaptureBackend {
    val state: StateFlow<MediaProjectionSessionSnapshot>
    fun isMediaProjectionSupported(): Boolean
    fun createScreenCaptureIntent(): Intent?
    fun onConsentResult(resultCode: Int, data: Intent?): Boolean
    fun stopSession(revokedBySystem: Boolean = false)
    suspend fun captureScreenshot(includeBase64: Boolean = false): ScreenshotCaptureOutcome
}

open class MediaProjectionSessionManager(
    private val context: Context
) : ScreenshotCaptureBackend {

    private val _state = MutableStateFlow(
        if (isProjectionServiceAvailable()) {
            MediaProjectionSessionSnapshot()
        } else {
            MediaProjectionSessionSnapshot(
                consentState = MediaProjectionConsentState.UNSUPPORTED,
                statusReason = "MediaProjectionManager is not supported on this device.",
                isSessionActive = false
            )
        }
    )
    override val state: StateFlow<MediaProjectionSessionSnapshot> = _state.asStateFlow()

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentDensityDpi: Int = 320
    private var projectionCallback: MediaProjection.Callback? = null

    override fun isMediaProjectionSupported(): Boolean = isProjectionServiceAvailable()

    override fun createScreenCaptureIntent(): Intent? {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return null
        return runCatching { mgr.createScreenCaptureIntent() }.getOrNull()
    }

    override fun onConsentResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            _state.update {
                it.copy(
                    consentState = if (it.consentState == MediaProjectionConsentState.GRANTED_ACTIVE) {
                        MediaProjectionConsentState.GRANTED_ACTIVE
                    } else {
                        MediaProjectionConsentState.NOT_GRANTED
                    },
                    statusReason = "Screen capture permission was not granted by the user."
                )
            }
            return false
        }

        if (!isProjectionServiceAvailable()) {
            _state.value = MediaProjectionSessionSnapshot(
                consentState = MediaProjectionConsentState.UNSUPPORTED,
                statusReason = "MediaProjectionManager is unavailable on this device.",
                isSessionActive = false
            )
            return false
        }

        val serviceIntent = MediaProjectionCaptureService.buildStartIntent(
            context = context,
            resultCode = resultCode,
            resultData = data
        )
        return runCatching {
            ContextCompat.startForegroundService(context, serviceIntent)
            true
        }.getOrElse { err ->
            _state.update {
                it.copy(
                    consentState = MediaProjectionConsentState.REVOKED,
                    isSessionActive = false,
                    statusReason = "Unable to start screen-capture foreground service: ${err.message ?: "unknown error"}"
                )
            }
            false
        }
    }

    internal fun attachStartedProjection(resultCode: Int, data: Intent): Boolean {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return false

        releaseResourcesOnly()

        val projection = runCatching { mgr.getMediaProjection(resultCode, data) }.getOrNull()
        if (projection == null) {
            _state.value = MediaProjectionSessionSnapshot(
                consentState = MediaProjectionConsentState.REVOKED,
                isSessionActive = false,
                statusReason = "Android refused to create MediaProjection token. Request permission again."
            )
            return false
        }

        val (width, height, dpi) = resolveDisplayMetrics()
        currentWidth = width
        currentHeight = height
        currentDensityDpi = dpi

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                handleSystemStop()
            }

            override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
                if (newWidth > 0 && newHeight > 0 && (newWidth != currentWidth || newHeight != currentHeight)) {
                    recreateVirtualDisplay(newWidth, newHeight)
                }
            }
        }

        return try {
            // Mandatory on Android 14+ before createVirtualDisplay()
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            projectionCallback = callback
            mediaProjection = projection

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(Looper.getMainLooper())
            )

            _state.value = MediaProjectionSessionSnapshot(
                consentState = MediaProjectionConsentState.GRANTED_ACTIVE,
                statusReason = "Screen capture session is active (${width}x${height}). Tap Stop Session when done.",
                isSessionActive = true,
                width = width,
                height = height,
                lastCapturePath = _state.value.lastCapturePath,
                lastCaptureTimestampMillis = _state.value.lastCaptureTimestampMillis
            )
            true
        } catch (t: Throwable) {
            releaseResourcesOnly()
            _state.value = MediaProjectionSessionSnapshot(
                consentState = MediaProjectionConsentState.REVOKED,
                isSessionActive = false,
                statusReason = "MediaProjection initialization failed: ${t.message ?: "unknown error"}"
            )
            false
        }
    }

    override fun stopSession(revokedBySystem: Boolean) {
        val proj = mediaProjection
        releaseResourcesOnly()
        runCatching { proj?.stop() }
        runCatching {
            context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
        }
        _state.update { prev ->
            prev.copy(
                consentState = if (revokedBySystem) MediaProjectionConsentState.REVOKED else MediaProjectionConsentState.NOT_GRANTED,
                isSessionActive = false,
                statusReason = if (revokedBySystem) {
                    "Screen capture session was stopped or revoked by Android."
                } else {
                    "Screen capture session stopped by user. Grant permission again when needed."
                }
            )
        }
    }

    override suspend fun captureScreenshot(includeBase64: Boolean): ScreenshotCaptureOutcome =
        withContext(Dispatchers.IO) {
            val snap = _state.value
            if (snap.consentState == MediaProjectionConsentState.UNSUPPORTED) {
                return@withContext ScreenshotCaptureOutcome(
                    success = false,
                    message = "MediaProjection screen capture is unsupported on this device.",
                    errorCode = "MEDIA_PROJECTION_UNSUPPORTED",
                    requiresConsent = false
                )
            }
            val reader = imageReader
            val projection = mediaProjection
            if (!snap.isSessionActive ||
                snap.consentState != MediaProjectionConsentState.GRANTED_ACTIVE ||
                reader == null ||
                projection == null
            ) {
                return@withContext ScreenshotCaptureOutcome(
                    success = false,
                    message = "Screen capture consent is required before calling take_screenshot. Use 'Grant Screen Capture Permission' in Chat or Connections -> Capabilities.",
                    errorCode = "MEDIA_PROJECTION_CONSENT_REQUIRED",
                    requiresConsent = true
                )
            }

            var acquiredImage: Image? = null
            var bitmap: Bitmap? = null
            try {
                // Poll briefly for the latest available frame
                repeat(15) {
                    acquiredImage = runCatching { reader.acquireLatestImage() }.getOrNull()
                    if (acquiredImage != null) return@repeat
                    delay(80L)
                }

                val image = acquiredImage
                if (image == null) {
                    return@withContext ScreenshotCaptureOutcome(
                        success = false,
                        message = "No display frame available yet from MediaProjection (window may be idle, protected by FLAG_SECURE, or session stopped).",
                        errorCode = "SCREENSHOT_FRAME_UNAVAILABLE",
                        requiresConsent = false
                    )
                }

                val plane = image.planes.firstOrNull()
                if (plane == null) {
                    return@withContext ScreenshotCaptureOutcome(
                        success = false,
                        message = "Captured image contained no pixel planes.",
                        errorCode = "SCREENSHOT_INVALID_IMAGE"
                    )
                }

                val imgWidth = image.width.coerceAtLeast(1)
                val imgHeight = image.height.coerceAtLeast(1)
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = (rowStride - pixelStride * imgWidth).coerceAtLeast(0)
                val paddedBitmap = Bitmap.createBitmap(
                    imgWidth + rowPadding / pixelStride.coerceAtLeast(1),
                    imgHeight,
                    Bitmap.Config.ARGB_8888
                )
                plane.buffer.rewind()
                paddedBitmap.copyPixelsFromBuffer(plane.buffer)
                bitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(paddedBitmap, 0, 0, imgWidth, imgHeight).also {
                        paddedBitmap.recycle()
                    }
                } else {
                    paddedBitmap
                }

                val outDir = File(context.cacheDir, "screenshots").apply { mkdirs() }
                val ts = System.currentTimeMillis()
                val outFile = File(outDir, "nexa_screen_$ts.png")
                FileOutputStream(outFile).use { fos ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                        return@withContext ScreenshotCaptureOutcome(
                            success = false,
                            message = "Failed to encode captured frame as PNG.",
                            errorCode = "SCREENSHOT_ENCODE_FAILED"
                        )
                    }
                }

                if (!outFile.exists() || outFile.length() <= 0L) {
                    return@withContext ScreenshotCaptureOutcome(
                        success = false,
                        message = "Screenshot PNG file was not written cleanly.",
                        errorCode = "SCREENSHOT_EMPTY_FILE"
                    )
                }

                val encodedBase64 = if (includeBase64) {
                    encodeVisionThumbnailBase64(bitmap)
                } else {
                    null
                }

                _state.update {
                    it.copy(
                        lastCapturePath = outFile.absolutePath,
                        lastCaptureTimestampMillis = ts,
                        width = imgWidth,
                        height = imgHeight
                    )
                }

                ScreenshotCaptureOutcome(
                    success = true,
                    filePath = outFile.absolutePath,
                    width = imgWidth,
                    height = imgHeight,
                    mimeType = "image/png",
                    timestampMillis = ts,
                    base64Data = encodedBase64,
                    message = "Captured ${imgWidth}x${imgHeight} screenshot to ${outFile.name}."
                )
            } catch (t: Throwable) {
                ScreenshotCaptureOutcome(
                    success = false,
                    message = "Screenshot capture failed: ${t.message ?: "unknown error"}",
                    errorCode = "SCREENSHOT_CAPTURE_FAILED"
                )
            } finally {
                runCatching { acquiredImage?.close() }
                runCatching { bitmap?.recycle() }
            }
        }

    private fun handleSystemStop() {
        releaseResourcesOnly()
        runCatching {
            context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
        }
        _state.update {
            it.copy(
                consentState = MediaProjectionConsentState.REVOKED,
                isSessionActive = false,
                statusReason = "Screen capture permission was revoked or ended by Android."
            )
        }
    }

    private fun recreateVirtualDisplay(newWidth: Int, newHeight: Int) {
        val projection = mediaProjection ?: return
        runCatching {
            virtualDisplay?.release()
            imageReader?.close()
            currentWidth = newWidth
            currentHeight = newHeight
            val reader = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            virtualDisplay = projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                newWidth,
                newHeight,
                currentDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(Looper.getMainLooper())
            )
            _state.update { it.copy(width = newWidth, height = newHeight) }
        }
    }

    private fun releaseResourcesOnly() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        val cb = projectionCallback
        if (cb != null) {
            runCatching { mediaProjection?.unregisterCallback(cb) }
        }
        projectionCallback = null
        mediaProjection = null
    }

    private fun isProjectionServiceAvailable(): Boolean {
        return runCatching {
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager != null
        }.getOrDefault(false)
    }

    private fun resolveDisplayMetrics(): Triple<Int, Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val dm = context.resources.displayMetrics
        val density = dm.densityDpi.coerceAtLeast(160)
        val rawWidth = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && wm != null) {
            wm.currentWindowMetrics.bounds.width()
        } else {
            dm.widthPixels
        }.coerceAtLeast(720)
        val rawHeight = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && wm != null) {
            wm.currentWindowMetrics.bounds.height()
        } else {
            dm.heightPixels
        }.coerceAtLeast(1280)

        // Scale down oversized displays smoothly for fast PNG capture
        val maxDim = 1280
        val maxSide = maxOf(rawWidth, rawHeight)
        return if (maxSide > maxDim) {
            val scale = maxDim.toFloat() / maxSide.toFloat()
            Triple((rawWidth * scale).toInt().coerceAtLeast(360), (rawHeight * scale).toInt().coerceAtLeast(640), density)
        } else {
            Triple(rawWidth, rawHeight, density)
        }
    }

    private fun encodeVisionThumbnailBase64(bitmap: Bitmap): String? {
        return runCatching {
            val maxDim = 1024
            val w = bitmap.width
            val h = bitmap.height
            val target = if (w > maxDim || h > maxDim) {
                val scale = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
                Bitmap.createScaledBitmap(bitmap, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
            } else {
                bitmap
            }
            try {
                ByteArrayOutputStream().use { baos ->
                    target.compress(Bitmap.CompressFormat.JPEG, 82, baos)
                    Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                }
            } finally {
                if (target !== bitmap) {
                    target.recycle()
                }
            }
        }.getOrNull()
    }

    internal fun setTestSnapshotForTesting(snapshot: MediaProjectionSessionSnapshot) {
        _state.value = snapshot
    }

    companion object {
        private const val VIRTUAL_DISPLAY_NAME = "NexaScreenCapture"
    }
}
