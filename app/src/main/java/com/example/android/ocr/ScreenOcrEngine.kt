package com.example.android.ocr

import android.graphics.Bitmap
import com.example.domain.model.OcrTextRegion
import com.example.domain.model.ObservedUiNode
import com.example.domain.model.ScreenObservation
import com.example.domain.model.UiBounds
import com.example.domain.model.UiNodeSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

interface ScreenOcrProcessor {
    suspend fun recognizeText(bitmap: Bitmap): List<OcrTextRegion>
}

class MlKitScreenOcrProcessor(
    private val timeoutMillis: Long = 1_800L,
    private val maxRegions: Int = 40
) : ScreenOcrProcessor {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun recognizeText(bitmap: Bitmap): List<OcrTextRegion> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val regions = mutableListOf<OcrTextRegion>()
                        for (block in visionText.textBlocks) {
                            for (line in block.lines) {
                                if (regions.size >= maxRegions) break
                                val raw = line.text.replace(Regex("\\s+"), " ").trim()
                                if (raw.isEmpty()) continue
                                val box = line.boundingBox ?: block.boundingBox ?: continue
                                val safe = ScreenOcrSanitizer.sanitize(raw)
                                val lineConfidence = line.confidence.takeIf { it > 0f } ?: 0.82f
                                regions.add(
                                    OcrTextRegion(
                                        index = regions.size,
                                        text = safe.take(120),
                                        bounds = UiBounds(
                                            left = box.left.coerceAtLeast(0),
                                            top = box.top.coerceAtLeast(0),
                                            right = box.right.coerceAtLeast(box.left + 1),
                                            bottom = box.bottom.coerceAtLeast(box.top + 1)
                                        ),
                                        confidence = lineConfidence.coerceIn(0.1f, 1.0f)
                                    )
                                )
                            }
                            if (regions.size >= maxRegions) break
                        }
                        if (cont.isActive) cont.resume(regions)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                    .addOnCanceledListener {
                        if (cont.isActive) cont.resume(emptyList())
                    }
            }
        }.orEmpty()
    }
}

object ScreenOcrSanitizer {
    private val CARD_OR_PIN_REGEX = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val SENSITIVE_PREFIX_REGEX = Regex(
        pattern = """(?i)\b(otp|passcode|password|pin|cvv|cvc|verification\s+code|security\s+code)\b\s*[:\-]?\s*([A-Za-z0-9*•]{3,16})"""
    )

    fun sanitize(text: String): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return ""
        if (CARD_OR_PIN_REGEX.containsMatchIn(clean)) {
            return "[REDACTED_SENSITIVE]"
        }
        val match = SENSITIVE_PREFIX_REGEX.find(clean)
        if (match != null) {
            return clean.replaceRange(match.range, "${match.groupValues[1]}: [REDACTED_SENSITIVE]")
        }
        return clean
    }
}

object SnapshotFusionHelper {

    /**
     * Merges OCR regions into an existing accessibility ScreenObservation, adding
     * virtual OCR_FUSED nodes only where accessibility nodes have no overlapping label.
     */
    fun fuse(
        observation: ScreenObservation,
        ocrRegions: List<OcrTextRegion>,
        maxTotalNodes: Int = 80
    ): ScreenObservation {
        if (ocrRegions.isEmpty()) return observation

        val sanitizedRegions = ocrRegions.map { region ->
            region.copy(text = ScreenOcrSanitizer.sanitize(region.text))
        }.filter { it.text.isNotBlank() }

        val mergedNodes = observation.nodes.toMutableList()
        for (region in sanitizedRegions) {
            if (mergedNodes.size >= maxTotalNodes) break
            val normRegion = region.text.lowercase()
            val alreadyCovered = mergedNodes.any { existing ->
                val existingLabel = listOfNotNull(existing.text, existing.contentDescription, existing.hintText)
                    .joinToString(" ")
                    .lowercase()
                val textOverlap = existingLabel.contains(normRegion) || normRegion.contains(existingLabel.takeIf { it.length >= 4 } ?: "\u0000")
                val highSpatialOverlap = intersectionOverSmaller(existing.bounds, region.bounds) >= 0.55f && existingLabel.isNotBlank()
                textOverlap || highSpatialOverlap
            }
            if (!alreadyCovered) {
                mergedNodes.add(
                    ObservedUiNode(
                        index = mergedNodes.size,
                        viewId = null,
                        className = "OcrTextRegion",
                        role = "VisualTextTarget",
                        text = region.text,
                        contentDescription = null,
                        hintText = null,
                        bounds = region.bounds,
                        clickable = true,
                        editable = false,
                        scrollable = false,
                        checkable = false,
                        checked = false,
                        focused = false,
                        enabled = true,
                        isPassword = false,
                        isSensitiveRedacted = region.text.contains("[REDACTED_SENSITIVE]"),
                        depth = 0,
                        supportedActions = listOf("TAP_COORDINATES"),
                        source = UiNodeSource.OCR_FUSED
                    )
                )
            }
        }

        val ocrAdded = mergedNodes.size - observation.nodes.size
        val summarySuffix = if (ocrAdded > 0) " (+ $ocrAdded OCR visual region(s))" else ""
        return observation.copy(
            nodes = mergedNodes,
            ocrRegions = sanitizedRegions,
            isInteractive = observation.isInteractive || mergedNodes.isNotEmpty(),
            isRestrictedWindow = observation.isRestrictedWindow && mergedNodes.isEmpty(),
            hierarchyCompleteness = max(observation.hierarchyCompleteness, if (sanitizedRegions.isNotEmpty()) 0.65f else 0f),
            screenSummary = (observation.screenSummary + summarySuffix).trim()
        )
    }

    private fun intersectionOverSmaller(a: UiBounds, b: UiBounds): Float {
        if (!a.intersects(b)) return 0f
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val interArea = (right - left).coerceAtLeast(0).toLong() * (bottom - top).coerceAtLeast(0).toLong()
        val minArea = min(a.area(), b.area()).coerceAtLeast(1L)
        return (interArea.toFloat() / minArea.toFloat()).coerceIn(0f, 1f)
    }
}
