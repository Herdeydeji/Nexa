package com.example.domain.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.ComposerAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

interface AttachmentProcessor {
    suspend fun process(
        attachments: List<ComposerAttachment>,
        modelSupportsVision: Boolean,
        modelDisplayName: String
    ): List<AttachmentPayload>
}

class AndroidAttachmentProcessor(
    private val context: Context
) : AttachmentProcessor {

    override suspend fun process(
        attachments: List<ComposerAttachment>,
        modelSupportsVision: Boolean,
        modelDisplayName: String
    ): List<AttachmentPayload> = withContext(Dispatchers.IO) {
        attachments.map { item ->
            processSingle(item, modelSupportsVision, modelDisplayName)
        }
    }

    private fun processSingle(
        item: ComposerAttachment,
        modelSupportsVision: Boolean,
        modelDisplayName: String
    ): AttachmentPayload {
        val uri = runCatching { Uri.parse(item.sourceUri) }.getOrNull()
            ?: return unsupported(item, "Could not resolve attachment URI for ${item.displayName}.")

        val resolvedMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: item.mimeType

        val isImage = item.isImage || resolvedMime.startsWith("image/", ignoreCase = true)
        if (isImage) {
            if (!modelSupportsVision) {
                return unsupported(
                    item,
                    "${item.displayName} was not sent: $modelDisplayName does not support vision inputs."
                )
            }
            return readAndEncodeImage(item, uri, resolvedMime)
        }

        if (isSupportedTextFile(item.displayName, resolvedMime)) {
            return readTextAttachment(item, uri, resolvedMime)
        }

        return unsupported(
            item,
            "${item.displayName} ($resolvedMime) is not readable as plain text or supported image in Phase 2."
        )
    }

    private fun readTextAttachment(
        item: ComposerAttachment,
        uri: Uri,
        mimeType: String
    ): AttachmentPayload {
        return try {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (total < MAX_TEXT_BYTES + 1) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    val allowed = minOf(read, MAX_TEXT_BYTES + 1 - total)
                    buffer.write(chunk, 0, allowed)
                    total += allowed
                }
                buffer.toByteArray()
            } ?: return unsupported(item, "Could not open ${item.displayName} for reading.")

            val truncated = rawBytes.size > MAX_TEXT_BYTES
            val textSlice = if (truncated) rawBytes.copyOf(MAX_TEXT_BYTES) else rawBytes
            val decoded = String(textSlice, Charsets.UTF_8) +
                if (truncated) "\n\n[Truncated after ${MAX_TEXT_BYTES / 1024} KB]" else ""

            AttachmentPayload(
                id = item.id,
                displayName = item.displayName,
                mimeType = mimeType,
                sourceUri = item.sourceUri,
                textContent = decoded,
                byteSize = textSlice.size.toLong(),
                isSupported = true
            )
        } catch (_: Exception) {
            unsupported(item, "Failed to read ${item.displayName}.")
        }
    }

    private fun readAndEncodeImage(
        item: ComposerAttachment,
        uri: Uri,
        mimeType: String
    ): AttachmentPayload {
        return try {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return unsupported(item, "Could not open image ${item.displayName}.")
            if (rawBytes.size > MAX_RAW_IMAGE_BYTES) {
                return unsupported(
                    item,
                    "Image ${item.displayName} exceeds ${MAX_RAW_IMAGE_BYTES / (1024 * 1024)} MB limit."
                )
            }

            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                ?: return unsupported(item, "Could not decode image ${item.displayName}.")

            val scaled = scaleDownIfNeeded(bitmap, MAX_IMAGE_DIMENSION)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()

            val jpegBytes = out.toByteArray()
            val encoded = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            AttachmentPayload(
                id = item.id,
                displayName = item.displayName,
                mimeType = "image/jpeg",
                sourceUri = item.sourceUri,
                imageBase64 = encoded,
                byteSize = jpegBytes.size.toLong(),
                isSupported = true
            )
        } catch (_: Exception) {
            unsupported(item, "Could not prepare image ${item.displayName}.")
        }
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val newW = (width * ratio).toInt().coerceAtLeast(1)
        val newH = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun isSupportedTextFile(displayName: String, mimeType: String): Boolean {
        val lowerMime = mimeType.lowercase()
        if (lowerMime.startsWith("text/") ||
            lowerMime == "application/json" ||
            lowerMime == "application/xml" ||
            lowerMime == "application/javascript" ||
            lowerMime == "application/x-yaml"
        ) {
            return true
        }
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    private fun unsupported(item: ComposerAttachment, reason: String) = AttachmentPayload(
        id = item.id,
        displayName = item.displayName,
        mimeType = item.mimeType,
        sourceUri = item.sourceUri,
        isSupported = false,
        unsupportedReason = reason
    )

    private companion object {
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_RAW_IMAGE_BYTES = 12 * 1024 * 1024
        const val MAX_IMAGE_DIMENSION = 1024
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "json", "kt", "kts", "java", "py", "js", "ts", "tsx",
            "jsx", "xml", "yaml", "yml", "toml", "csv", "log", "html", "css", "sh", "sql",
            "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "swift", "ini", "conf", "env"
        )
    }
}
