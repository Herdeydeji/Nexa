package com.example.android.accessibility

import com.example.domain.model.GroundingMatchExplanation
import com.example.domain.model.ObservedUiNode
import com.example.domain.model.ScreenObservation
import com.example.domain.model.TargetResolutionSpec
import com.example.domain.model.UiBounds
import com.example.domain.model.UiElementGroundingLevel
import com.example.domain.model.UiNodeSource
import kotlin.math.max
import kotlin.math.min

sealed interface GroundingOutcome {
    data class Resolved(
        val node: ObservedUiNode,
        val explanation: GroundingMatchExplanation
    ) : GroundingOutcome

    data class Ambiguous(
        val candidates: List<ObservedUiNode>,
        val explanation: GroundingMatchExplanation,
        val reason: String
    ) : GroundingOutcome

    data class LowConfidence(
        val candidates: List<ObservedUiNode>,
        val explanation: GroundingMatchExplanation,
        val reason: String
    ) : GroundingOutcome

    data class StaleSnapshot(
        val expectedSnapshotId: Long,
        val actualSnapshotId: Long,
        val reason: String
    ) : GroundingOutcome

    data class NotFound(val reason: String) : GroundingOutcome
}

/**
 * 9-level UI element grounding engine with snapshot validation, parent/sibling
 * contextual disambiguation, overlap/occlusion checks, OCR region fallback, and
 * structured match explanations.
 */
object UiElementGroundingEngine {

    private const val MIN_CONFIDENCE_THRESHOLD = 0.55f

    fun resolve(
        observation: ScreenObservation,
        spec: TargetResolutionSpec,
        preferEditable: Boolean = false
    ): GroundingOutcome {
        val rawNodes = observation.nodes
        if (rawNodes.isEmpty()) {
            return GroundingOutcome.NotFound("No UI or OCR elements observed on screen.")
        }

        // Strict snapshot validation for index-pinned calls
        if (spec.expectedSnapshotId != null &&
            observation.snapshotId > 0L &&
            spec.expectedSnapshotId != observation.snapshotId &&
            spec.nodeIndex != null &&
            spec.viewId.isNullOrBlank() &&
            spec.text.isNullOrBlank() &&
            spec.contentDescription.isNullOrBlank()
        ) {
            return GroundingOutcome.StaleSnapshot(
                expectedSnapshotId = spec.expectedSnapshotId,
                actualSnapshotId = observation.snapshotId,
                reason = "Target pinned to snapshot #${spec.expectedSnapshotId}, but current screen is snapshot #${observation.snapshotId}."
            )
        }

        // Level 1: Exact node index + matching snapshot ID
        spec.nodeIndex?.let { idx ->
            val byIndex = rawNodes.firstOrNull { it.index == idx }
            if (byIndex != null) {
                val snapshotMatched = spec.expectedSnapshotId == null ||
                    observation.snapshotId == 0L ||
                    spec.expectedSnapshotId == observation.snapshotId
                if (snapshotMatched) {
                    val obstructed = checkOcclusion(byIndex, rawNodes)
                    return GroundingOutcome.Resolved(
                        node = byIndex,
                        explanation = GroundingMatchExplanation(
                            level = UiElementGroundingLevel.EXACT_SNAPSHOT_NODE,
                            confidence = if (obstructed) 0.72f else 0.99f,
                            rationale = "Matched exact node #$idx on snapshot #${observation.snapshotId}${if (obstructed) " (overlap detected)" else ""}.",
                            snapshotId = observation.snapshotId,
                            overlapDetected = obstructed
                        )
                    )
                }
            }
        }

        val interactivePool = rawNodes.filter {
            it.enabled && (it.bounds.width > 0 || it.bounds.height > 0 || it.editable || it.clickable || it.source == UiNodeSource.OCR_FUSED)
        }.ifEmpty { rawNodes.filter { it.enabled } }.ifEmpty { rawNodes }

        val candidates = if (preferEditable || spec.editableOnly) {
            interactivePool.filter { it.editable || it.role.contains("Field", ignoreCase = true) }
                .ifEmpty { interactivePool }
        } else {
            interactivePool
        }

        // Level 2: Exact or suffix resource view ID
        spec.viewId?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { vid ->
            val cleanVid = vid.substringAfterLast('/')
            val exactId = candidates.filter { normOrNull(it.viewId?.substringAfterLast('/')) == cleanVid }
            evaluateTierMatches(
                matches = exactId,
                allNodes = rawNodes,
                spec = spec,
                observation = observation,
                level = UiElementGroundingLevel.RESOURCE_VIEW_ID,
                baseConfidence = 0.96f,
                tierDesc = "view_id='$vid'"
            )?.let { return it }

            val partialId = candidates.filter { normOrNull(it.viewId)?.contains(cleanVid) == true }
            evaluateTierMatches(
                matches = partialId,
                allNodes = rawNodes,
                spec = spec,
                observation = observation,
                level = UiElementGroundingLevel.RESOURCE_VIEW_ID,
                baseConfidence = 0.88f,
                tierDesc = "view_id~='$vid'"
            )?.let { return it }
        }

        // Level 3: Exact contentDescription
        spec.contentDescription?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { q ->
            val exactDesc = candidates.filter { normOrNull(it.contentDescription) == q }
            evaluateTierMatches(
                matches = exactDesc,
                allNodes = rawNodes,
                spec = spec,
                observation = observation,
                level = UiElementGroundingLevel.EXACT_CONTENT_DESCRIPTION,
                baseConfidence = 0.94f,
                tierDesc = "content_description='$q'"
            )?.let { return it }
        }

        // Level 4: Exact visible text or hint
        spec.text?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { q ->
            val exactText = candidates.filter {
                it.source == UiNodeSource.ACCESSIBILITY && (normOrNull(it.text) == q || normOrNull(it.hintText) == q)
            }
            evaluateTierMatches(
                matches = exactText,
                allNodes = rawNodes,
                spec = spec,
                observation = observation,
                level = UiElementGroundingLevel.EXACT_VISIBLE_TEXT,
                baseConfidence = 0.92f,
                tierDesc = "text='$q'"
            )?.let { return it }

            val exactDescFallback = candidates.filter { normOrNull(it.contentDescription) == q }
            evaluateTierMatches(
                matches = exactDescFallback,
                allNodes = rawNodes,
                spec = spec,
                observation = observation,
                level = UiElementGroundingLevel.EXACT_CONTENT_DESCRIPTION,
                baseConfidence = 0.90f,
                tierDesc = "content_description='$q'"
            )?.let { return it }
        }

        // Level 5: Case-insensitive normalized text/hint/description across punctuation variants
        val queryStr = spec.text ?: spec.contentDescription
        queryStr?.let(::looseNorm)?.takeIf { it.isNotEmpty() }?.let { looseQ ->
            val looseMatches = candidates.filter {
                it.source == UiNodeSource.ACCESSIBILITY && (
                    looseNormOrNull(it.text) == looseQ ||
                        looseNormOrNull(it.hintText) == looseQ ||
                        looseNormOrNull(it.contentDescription) == looseQ
                    )
            }
            evaluateTierMatches(
                matches = looseMatches,
                allNodes = rawNodes,
                spec = spec,
                observation = observation,
                level = UiElementGroundingLevel.NORMALIZED_TEXT_OR_HINT,
                baseConfidence = 0.85f,
                tierDesc = "normalized '$looseQ'"
            )?.let { return it }
        }

        // Level 6: Role + surrounding parent/sibling context (e.g., anchorContext)
        if (!spec.role.isNullOrBlank() || !spec.anchorContext.isNullOrBlank()) {
            val anchorNorm = spec.anchorContext?.let(::norm)?.takeIf { it.isNotEmpty() }
            val roleNorm = spec.role?.let(::norm)?.takeIf { it.isNotEmpty() }
            val roleContextMatches = candidates.filter { node ->
                val roleOk = roleNorm == null || normOrNull(node.role) == roleNorm
                val anchorOk = anchorNorm == null || hasContextMatch(node, rawNodes, anchorNorm)
                roleOk && anchorOk
            }
            if (roleContextMatches.isNotEmpty() && (anchorNorm != null || roleContextMatches.size == 1)) {
                evaluateTierMatches(
                    matches = roleContextMatches,
                    allNodes = rawNodes,
                    spec = spec,
                    observation = observation,
                    level = UiElementGroundingLevel.ROLE_AND_CONTEXT,
                    baseConfidence = 0.80f,
                    tierDesc = "role/context '${spec.role ?: ""}' + '${spec.anchorContext ?: ""}'"
                )?.let { return it }
            }
        }

        // Level 7: Relative screen position / quarter anchor filter on partial matches
        spec.preferredQuarter?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { quarter ->
            val quarterFiltered = candidates.filter { inPreferredQuarter(it, observation, quarter) }
                .filter { node ->
                    val q = queryStr?.let(::norm)
                    q.isNullOrBlank() ||
                        normOrNull(node.text)?.contains(q) == true ||
                        normOrNull(node.contentDescription)?.contains(q) == true ||
                        normOrNull(node.hintText)?.contains(q) == true
                }
            evaluateTierMatches(
                matches = quarterFiltered,
                allNodes = rawNodes,
                spec = spec,
                observation = observation,
                level = UiElementGroundingLevel.RELATIVE_BOUNDS_ANCHOR,
                baseConfidence = 0.76f,
                tierDesc = "region='$quarter'"
            )?.let { return it }
        }

        // Level 8: On-device OCR visual text region fallback
        if (spec.allowOcrFallback) {
            queryStr?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { q ->
                val ocrExact = candidates.filter {
                    it.source == UiNodeSource.OCR_FUSED && (normOrNull(it.text) == q || looseNormOrNull(it.text) == looseNorm(q))
                }
                evaluateTierMatches(
                    matches = ocrExact,
                    allNodes = rawNodes,
                    spec = spec,
                    observation = observation,
                    level = UiElementGroundingLevel.OCR_TEXT_MATCH,
                    baseConfidence = 0.74f,
                    tierDesc = "OCR text '$q'"
                )?.let { return it }

                val ocrPartial = candidates.filter {
                    it.source == UiNodeSource.OCR_FUSED && normOrNull(it.text)?.contains(q) == true
                }
                evaluateTierMatches(
                    matches = ocrPartial,
                    allNodes = rawNodes,
                    spec = spec,
                    observation = observation,
                    level = UiElementGroundingLevel.OCR_TEXT_MATCH,
                    baseConfidence = 0.64f,
                    tierDesc = "OCR partial '$q'"
                )?.let { return it }
            }
        }

        // Level 9: Unambiguous partial / fuzzy semantic match
        spec.text?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { q ->
            val partialText = candidates.filter {
                normOrNull(it.text)?.contains(q) == true || normOrNull(it.hintText)?.contains(q) == true
            }
            evaluateTierMatches(
                matches = partialText,
                allNodes = rawNodes,
                spec = spec,
                observation = observation,
                level = UiElementGroundingLevel.FUZZY_PARTIAL_SEMANTIC,
                baseConfidence = 0.68f,
                tierDesc = "partial text '$q'"
            )?.let { return it }

            val partialDesc = candidates.filter { normOrNull(it.contentDescription)?.contains(q) == true }
            evaluateTierMatches(
                matches = partialDesc,
                allNodes = rawNodes,
                spec = spec,
                observation = observation,
                level = UiElementGroundingLevel.FUZZY_PARTIAL_SEMANTIC,
                baseConfidence = 0.63f,
                tierDesc = "partial desc '$q'"
            )?.let { return it }
        }

        spec.contentDescription?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { q ->
            val partialDesc = candidates.filter { normOrNull(it.contentDescription)?.contains(q) == true }
            evaluateTierMatches(
                matches = partialDesc,
                allNodes = rawNodes,
                spec = spec,
                observation = observation,
                level = UiElementGroundingLevel.FUZZY_PARTIAL_SEMANTIC,
                baseConfidence = 0.63f,
                tierDesc = "partial content_description '$q'"
            )?.let { return it }
        }

        // Token-overlap fuzzy fallback when query has multiple words
        queryStr?.let { tokenize(it) }?.takeIf { it.size >= 2 }?.let { tokens ->
            val scored = candidates.mapNotNull { node ->
                val label = listOfNotNull(node.text, node.contentDescription, node.hintText).joinToString(" ").lowercase()
                if (label.isBlank()) return@mapNotNull null
                val overlapCount = tokens.count { label.contains(it) }
                val ratio = overlapCount.toFloat() / tokens.size.toFloat()
                if (overlapCount >= 1 && ratio >= 0.45f) (ratio * 0.65f) to node else null
            }.sortedByDescending { it.first }

            if (scored.isNotEmpty()) {
                val (topScore, topNode) = scored.first()
                if (topScore < MIN_CONFIDENCE_THRESHOLD) {
                    return GroundingOutcome.LowConfidence(
                        candidates = scored.take(4).map { it.second },
                        explanation = GroundingMatchExplanation(
                            level = UiElementGroundingLevel.FUZZY_PARTIAL_SEMANTIC,
                            confidence = topScore,
                            rationale = "Fuzzy match confidence (${"%.2f".format(topScore)}) below safe threshold.",
                            snapshotId = observation.snapshotId
                        ),
                        reason = "Closest UI element '${describeCandidate(topNode)}' scored only ${(topScore * 100).toInt()}% confidence; re-inspecting or asking user is safer than guessing."
                    )
                }
                if (scored.size == 1 || (topScore - scored[1].first) >= 0.15f) {
                    val obstructed = checkOcclusion(topNode, rawNodes)
                    return GroundingOutcome.Resolved(
                        node = topNode,
                        explanation = GroundingMatchExplanation(
                            level = UiElementGroundingLevel.FUZZY_PARTIAL_SEMANTIC,
                            confidence = topScore,
                            rationale = "Best fuzzy token overlap (${(topScore * 100).toInt()}%) cleanly ahead of alternatives.",
                            snapshotId = observation.snapshotId,
                            overlapDetected = obstructed
                        )
                    )
                }
                return GroundingOutcome.Ambiguous(
                    candidates = scored.take(4).map { it.second },
                    explanation = GroundingMatchExplanation(
                        level = UiElementGroundingLevel.FUZZY_PARTIAL_SEMANTIC,
                        confidence = topScore,
                        rationale = "Multiple fuzzy token matches with similar scores.",
                        snapshotId = observation.snapshotId
                    ),
                    reason = "Ambiguous partial matches for '$queryStr': ${scored.size} candidate elements."
                )
            }
        }

        if (preferEditable || spec.editableOnly) {
            val focusedOrEditable = candidates.firstOrNull { it.focused && it.editable }
                ?: candidates.firstOrNull { it.editable }
            if (focusedOrEditable != null) {
                return GroundingOutcome.Resolved(
                    node = focusedOrEditable,
                    explanation = GroundingMatchExplanation(
                        level = UiElementGroundingLevel.ROLE_AND_CONTEXT,
                        confidence = 0.80f,
                        rationale = "Resolved active/only editable field on screen.",
                        snapshotId = observation.snapshotId
                    )
                )
            }
        }

        return GroundingOutcome.NotFound("No matching enabled UI or OCR element found.")
    }

    private fun evaluateTierMatches(
        matches: List<ObservedUiNode>,
        allNodes: List<ObservedUiNode>,
        spec: TargetResolutionSpec,
        observation: ScreenObservation,
        level: UiElementGroundingLevel,
        baseConfidence: Float,
        tierDesc: String
    ): GroundingOutcome? {
        if (matches.isEmpty()) return null
        if (matches.size == 1) {
            val chosen = matches.first()
            val obstructed = checkOcclusion(chosen, allNodes)
            return GroundingOutcome.Resolved(
                node = chosen,
                explanation = GroundingMatchExplanation(
                    level = level,
                    confidence = baseConfidence,
                    rationale = "Unique match via $tierDesc (tier ${level.tier}).",
                    snapshotId = observation.snapshotId,
                    overlapDetected = obstructed
                )
            )
        }

        // First attempt context disambiguation via anchorContext, preferredQuarter, or clickability
        val anchorNorm = spec.anchorContext?.let(::norm)?.takeIf { it.isNotEmpty() }
        if (anchorNorm != null) {
            val contextFiltered = matches.filter { hasContextMatch(it, allNodes, anchorNorm) }
            if (contextFiltered.size == 1) {
                val chosen = contextFiltered.first()
                return GroundingOutcome.Resolved(
                    node = chosen,
                    explanation = GroundingMatchExplanation(
                        level = level,
                        confidence = (baseConfidence - 0.03f).coerceAtLeast(0.60f),
                        rationale = "Disambiguated $tierDesc using nearby anchor '$anchorNorm'.",
                        snapshotId = observation.snapshotId,
                        overlapDetected = checkOcclusion(chosen, allNodes)
                    )
                )
            }
        }

        val quarterNorm = spec.preferredQuarter?.let(::norm)?.takeIf { it.isNotEmpty() }
        if (quarterNorm != null) {
            val regionFiltered = matches.filter { inPreferredQuarter(it, observation, quarterNorm) }
            if (regionFiltered.size == 1) {
                val chosen = regionFiltered.first()
                return GroundingOutcome.Resolved(
                    node = chosen,
                    explanation = GroundingMatchExplanation(
                        level = level,
                        confidence = (baseConfidence - 0.04f).coerceAtLeast(0.60f),
                        rationale = "Disambiguated $tierDesc using preferred region '$quarterNorm'.",
                        snapshotId = observation.snapshotId,
                        overlapDetected = checkOcclusion(chosen, allNodes)
                    )
                )
            }
        }

        val actionable = matches.filter { it.clickable || it.editable }
        if (actionable.size == 1) {
            val chosen = actionable.first()
            return GroundingOutcome.Resolved(
                node = chosen,
                explanation = GroundingMatchExplanation(
                    level = level,
                    confidence = baseConfidence,
                    rationale = "Selected unique interactive element among duplicates for $tierDesc.",
                    snapshotId = observation.snapshotId,
                    overlapDetected = checkOcclusion(chosen, allNodes)
                )
            )
        }

        val pool = actionable.ifEmpty { matches }.take(5)
        val distinctTexts = pool.map {
            "${normOrNull(it.text)}|${normOrNull(it.contentDescription)}|${it.viewId}|${it.bounds.toCompactString()}"
        }.toSet()
        return GroundingOutcome.Ambiguous(
            candidates = pool,
            explanation = GroundingMatchExplanation(
                level = level,
                confidence = 0.50f,
                rationale = "Multiple elements (${pool.size}) tied on $tierDesc.",
                snapshotId = observation.snapshotId
            ),
            reason = "Ambiguous UI target for $tierDesc: ${pool.size} matching nodes (${distinctTexts.size} distinct). Choose the intended element or specify context."
        )
    }

    /**
     * Detects whether a later overlapping non-descendant modal/overlay node covers
     * the center tap point of [target].
     */
    fun checkOcclusion(
        target: ObservedUiNode,
        allNodes: List<ObservedUiNode>,
        screenWidthPx: Int = 1080,
        screenHeightPx: Int = 2400
    ): Boolean {
        if (target.bounds.width <= 0 || target.bounds.height <= 0) return false
        val cx = target.bounds.centerX
        val cy = target.bounds.centerY
        val fullScreenArea = (screenWidthPx.coerceAtLeast(320).toLong() * screenHeightPx.coerceAtLeast(480).toLong())
        return allNodes.any { other ->
            val roleLower = other.role.lowercase()
            val viewLower = other.viewId?.lowercase().orEmpty()
            val textLower = listOfNotNull(other.text, other.contentDescription).joinToString(" ").lowercase()
            val looksModalOrBlocking = other.clickable ||
                roleLower.contains("dialog") ||
                roleLower.contains("modal") ||
                roleLower.contains("overlay") ||
                viewLower.contains("scrim") ||
                viewLower.contains("blocker") ||
                textLower.contains("modal") ||
                textLower.contains("blocker")
            val isFullScreenRoot = other.bounds.area() >= (fullScreenArea * 0.92).toLong() &&
                !viewLower.contains("scrim") &&
                !roleLower.contains("dialog")
            other.index > target.index &&
                other.parentIndex != target.index &&
                other.index !in target.childIndices &&
                other.enabled &&
                looksModalOrBlocking &&
                !isFullScreenRoot &&
                other.bounds.area() > 0 &&
                other.bounds.contains(cx, cy)
        }
    }

    private fun hasContextMatch(
        node: ObservedUiNode,
        allNodes: List<ObservedUiNode>,
        anchorNorm: String
    ): Boolean {
        val parent = node.parentIndex?.let { idx -> allNodes.firstOrNull { it.index == idx } }
        val siblings = if (node.parentIndex != null) {
            allNodes.filter { it.parentIndex == node.parentIndex && it.index != node.index }
        } else {
            // Spatial proximity check (~220px vertically)
            allNodes.filter {
                it.index != node.index && kotlin.math.abs(it.bounds.centerY - node.bounds.centerY) <= 220
            }
        }
        val pool = listOfNotNull(parent) + siblings
        return pool.any { neighbor ->
            val combined = listOfNotNull(neighbor.text, neighbor.contentDescription, neighbor.hintText, neighbor.viewId)
                .joinToString(" ")
                .lowercase()
            combined.contains(anchorNorm)
        }
    }

    private fun inPreferredQuarter(
        node: ObservedUiNode,
        obs: ScreenObservation,
        quarter: String
    ): Boolean {
        val h = obs.screenHeightPx.coerceAtLeast(1)
        val w = obs.screenWidthPx.coerceAtLeast(1)
        val cy = node.bounds.centerY
        val cx = node.bounds.centerX
        return when {
            quarter.contains("top") -> cy <= (h * 0.40f)
            quarter.contains("bottom") -> cy >= (h * 0.60f)
            quarter.contains("center") || quarter.contains("middle") -> cy in (h * 0.25f).toInt()..(h * 0.75f).toInt()
            quarter.contains("left") -> cx <= (w * 0.50f)
            quarter.contains("right") -> cx >= (w * 0.50f)
            else -> true
        }
    }

    fun describeCandidate(node: ObservedUiNode): String = buildString {
        append(
            node.text?.let { "'$it'" }
                ?: node.contentDescription?.let { "'$it'" }
                ?: node.viewId?.let { "#$it" }
                ?: "${node.role}[${node.index}]"
        )
        append(" (${node.role})")
    }

    private fun tokenize(value: String): List<String> =
        value.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }

    private fun norm(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase()

    private fun normOrNull(value: String?): String? =
        value?.let(::norm)?.takeIf { it.isNotEmpty() }

    private fun looseNorm(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun looseNormOrNull(value: String?): String? =
        value?.let(::looseNorm)?.takeIf { it.isNotEmpty() }
}
