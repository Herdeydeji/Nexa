package com.example.android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import com.example.domain.model.AppLookupOutcome
import com.example.domain.model.InstalledAppInfo

data class AppDiscoveryBatch(
    val apps: List<InstalledAppInfo>,
    val totalDiscovered: Int,
    val visibilityNotice: String,
    val offset: Int = 0,
    val hasMore: Boolean = false,
    val nextOffset: Int? = null
)

internal data class AppInventoryRecord(
    val info: InstalledAppInfo,
    val normalizedDisplayName: String,
    val compactNormalizedName: String,
    val normalizedPackageName: String,
    val installedOnDevice: Boolean = true
)

open class AndroidAppDiscovery(
    private val context: Context,
    private val packageManager: PackageManager = context.packageManager
) {

    open fun listLaunchableApps(
        query: String? = null,
        includeSystemApps: Boolean = true,
        limit: Int = DEFAULT_LIST_LIMIT,
        offset: Int = 0
    ): AppDiscoveryBatch = listInstalledApps(
        query = query,
        includeSystemApps = includeSystemApps,
        includeNonLaunchable = false,
        limit = limit,
        offset = offset
    )

    open fun listInstalledApps(
        query: String? = null,
        includeSystemApps: Boolean = true,
        includeNonLaunchable: Boolean = false,
        limit: Int = DEFAULT_LIST_LIMIT,
        offset: Int = 0
    ): AppDiscoveryBatch {
        val inventory = buildNormalizedInventory(
            includeSystemApps = includeSystemApps,
            includeNonLaunchable = includeNonLaunchable
        )
        val cleanQuery = collapseWhitespace(query.orEmpty())
        val filtered = if (cleanQuery.isEmpty()) {
            inventory.map { it.info }
        } else {
            inventory
                .mapNotNull { rec ->
                    val score = scoreRecord(rec, cleanQuery)
                    if (score > 0) score to rec.info else null
                }
                .sortedWith(
                    compareByDescending<Pair<Int, InstalledAppInfo>> { it.first }
                        .thenByDescending { it.second.isLaunchable }
                        .thenBy { it.second.appName.lowercase() }
                )
                .map { it.second }
        }

        val boundedLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val page = filtered.drop(safeOffset).take(boundedLimit)
        val hasMore = safeOffset + page.size < filtered.size
        val nextOffset = if (hasMore) safeOffset + page.size else null

        return AppDiscoveryBatch(
            apps = page,
            totalDiscovered = filtered.size,
            visibilityNotice = if (includeNonLaunchable) {
                "Queried visible installed applications and launcher intents (Android package visibility respected)."
            } else {
                "Queried launchable applications from Android launcher intents and visible installed packages (Android package visibility respected)."
            },
            offset = safeOffset,
            hasMore = hasMore,
            nextOffset = nextOffset
        )
    }

    open fun findApp(query: String): AppLookupOutcome {
        val clean = collapseWhitespace(query)
        if (clean.isEmpty()) {
            return AppLookupOutcome.NotFound(
                query = query,
                visibilityRestricted = false,
                reason = "Provide a non-empty application name or package identifier."
            )
        }

        val qLower = clean.lowercase()
        val qCompact = compactNormalize(clean)
        val fullInventory = buildNormalizedInventory(
            includeSystemApps = true,
            includeNonLaunchable = true
        )

        // Priority 1: Exact package-name match (case-insensitive)
        fullInventory.firstOrNull { it.normalizedPackageName == qLower }?.let { rec ->
            return if (rec.info.isLaunchable) {
                AppLookupOutcome.Found(app = rec.info)
            } else {
                AppLookupOutcome.NotLaunchable(
                    appName = rec.info.appName,
                    packageName = rec.info.packageName,
                    reason = "Package '${rec.info.packageName}' (${rec.info.appName}) is installed, but exposes no launchable main activity."
                )
            }
        }

        // Priority 2: Exact normalized display-name match among launchable apps
        val launchableRecords = fullInventory.filter { it.info.isLaunchable }
        val exactDisplayMatches = launchableRecords.filter { rec ->
            !isExcludedSelf(rec.info.packageName, qLower) &&
                (rec.normalizedDisplayName == qLower ||
                    (qCompact.isNotEmpty() && rec.compactNormalizedName == qCompact))
        }
        if (exactDisplayMatches.size == 1) {
            val winner = exactDisplayMatches.first().info
            return AppLookupOutcome.Found(app = winner)
        }
        if (exactDisplayMatches.size > 1) {
            val candidates = exactDisplayMatches.map { it.info }
            return AppLookupOutcome.Ambiguous(
                query = clean,
                candidates = candidates,
                reason = "Multiple installed applications match '$clean': ${candidates.joinToString { "${it.appName} (${it.packageName})" }}."
            )
        }

        // Priority 3: Partial display-name / package match among launchable apps
        val partialMatches = launchableRecords
            .mapNotNull { rec ->
                val score = scoreRecord(rec, clean)
                if (score > 0) score to rec else null
            }
            .sortedWith(
                compareByDescending<Pair<Int, AppInventoryRecord>> { it.first }
                    .thenBy { it.second.info.appName.length }
                    .thenBy { it.second.info.packageName }
            )

        if (partialMatches.isNotEmpty()) {
            val topScore = partialMatches.first().first
            val distinctCandidates = partialMatches.map { it.second.info }
            // Check ambiguity:
            // - multiple candidates share the top score tier, OR
            // - multiple distinct launchable candidates match at a moderate partial score (< 75)
            val tiedAtTop = partialMatches.count { it.first == topScore }
            val strongWordOrPrefixWinner = topScore >= 75 && tiedAtTop == 1
            if (distinctCandidates.size == 1 || strongWordOrPrefixWinner) {
                return AppLookupOutcome.Found(
                    app = distinctCandidates.first(),
                    alternatives = distinctCandidates.drop(1).take(4)
                )
            }
            return AppLookupOutcome.Ambiguous(
                query = clean,
                candidates = distinctCandidates.take(6),
                reason = "Multiple installed applications match '$clean': ${distinctCandidates.take(6).joinToString { "${it.appName} (${it.packageName})" }}."
            )
        }

        // Non-launchable match
        val nonLaunchableMatches = fullInventory
            .filter { !it.info.isLaunchable }
            .mapNotNull { rec ->
                val score = scoreRecord(rec, clean)
                if (score > 0) score to rec.info else null
            }
            .sortedByDescending { it.first }

        if (nonLaunchableMatches.isNotEmpty()) {
            val bestNonLaunchable = nonLaunchableMatches.first().second
            return AppLookupOutcome.NotLaunchable(
                appName = bestNonLaunchable.appName,
                packageName = bestNonLaunchable.packageName,
                reason = "Package '${bestNonLaunchable.packageName}' (${bestNonLaunchable.appName}) is installed, but exposes no launchable main activity."
            )
        }

        // Direct fallback for explicit package ID visible via PackageManager.getApplicationInfo
        val looksLikePackage = clean.contains('.') && !clean.contains(' ')
        if (looksLikePackage) {
            val appInfo = getApplicationInfoOrNull(clean)
            if (appInfo != null) {
                val launchIntent = runCatching { packageManager.getLaunchIntentForPackage(clean) }.getOrNull()
                val label = resolveAppLabel(appInfo, clean)
                if (launchIntent != null) {
                    val info = InstalledAppInfo(
                        appName = label,
                        packageName = clean,
                        launcherActivity = launchIntent.component?.className,
                        isLaunchable = true,
                        isSystemApp = isSystemAppInfo(appInfo)
                    )
                    return AppLookupOutcome.Found(app = info)
                }
                return AppLookupOutcome.NotLaunchable(
                    appName = label,
                    packageName = clean,
                    reason = "Package '$clean' ($label) is installed, but exposes no launchable main activity."
                )
            }
            return AppLookupOutcome.NotFound(
                query = clean,
                visibilityRestricted = true,
                reason = "Package '$clean' is either not installed or hidden by Android package visibility restrictions (no launcher intent exposed)."
            )
        }

        return AppLookupOutcome.NotFound(
            query = clean,
            visibilityRestricted = true,
            reason = "No installed launchable application matching '$clean' was found. It may not be installed or may have no launcher icon visible under Android package visibility rules."
        )
    }

    open fun canPackageHandleViewUri(packageName: String, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(packageName)
        }
        return queryActivities(intent).isNotEmpty()
    }

    open fun resolveLaunchIntent(packageName: String, launcherActivity: String? = null): Intent? {
        val fromPm = runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
        if (fromPm != null) {
            fromPm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return fromPm
        }
        if (!launcherActivity.isNullOrBlank()) {
            val component = ComponentName(packageName, launcherActivity)
            val explicit = Intent.makeMainActivity(component).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            val resolved = queryLauncherActivities(explicit)
            if (resolved.isNotEmpty()) {
                return explicit
            }
        }
        return null
    }

    private fun buildNormalizedInventory(
        includeSystemApps: Boolean,
        includeNonLaunchable: Boolean
    ): List<AppInventoryRecord> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = queryLauncherActivities(launcherIntent)
        val deduped = linkedMapOf<String, AppInventoryRecord>()

        for (ri in resolved) {
            val actInfo = ri.activityInfo ?: continue
            val pkg = actInfo.packageName?.takeIf { it.isNotBlank() } ?: continue
            if (deduped.containsKey(pkg)) continue
            val appInfo = actInfo.applicationInfo
            val isSystem = appInfo != null && isSystemAppInfo(appInfo)
            if (!includeSystemApps && isSystem) continue

            val label = runCatching { ri.loadLabel(packageManager)?.toString()?.trim() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: resolveAppLabel(appInfo, pkg)

            val info = InstalledAppInfo(
                appName = collapseWhitespace(label).ifBlank { label },
                packageName = pkg,
                launcherActivity = actInfo.name,
                isLaunchable = true,
                isSystemApp = isSystem
            )
            deduped[pkg] = toRecord(info)
        }

        // Also inspect visible installed packages via getInstalledApplications(), without capping before lookup
        val installedApps = getInstalledApplicationsSafe()
        for (appInfo in installedApps) {
            val pkg = appInfo.packageName?.takeIf { it.isNotBlank() } ?: continue
            if (deduped.containsKey(pkg)) continue
            val isSystem = isSystemAppInfo(appInfo)
            if (!includeSystemApps && isSystem) continue

            val launchIntent = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
            val launchable = launchIntent != null
            if (!launchable && !includeNonLaunchable) continue

            val label = resolveAppLabel(appInfo, pkg)
            val info = InstalledAppInfo(
                appName = collapseWhitespace(label).ifBlank { label },
                packageName = pkg,
                launcherActivity = launchIntent?.component?.className,
                isLaunchable = launchable,
                isSystemApp = isSystem
            )
            deduped[pkg] = toRecord(info)
        }

        return deduped.values.sortedBy { it.normalizedDisplayName }
    }

    private fun toRecord(info: InstalledAppInfo): AppInventoryRecord {
        val normalizedDisplay = collapseWhitespace(info.appName).lowercase()
        return AppInventoryRecord(
            info = info,
            normalizedDisplayName = normalizedDisplay,
            compactNormalizedName = compactNormalize(info.appName),
            normalizedPackageName = info.packageName.trim().lowercase()
        )
    }

    private fun queryLauncherActivities(intent: Intent): List<ResolveInfo> =
        queryActivities(intent)

    private fun queryActivities(intent: Intent): List<ResolveInfo> {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                ).ifEmpty {
                    packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
                }
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).ifEmpty {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(intent, 0)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun getInstalledApplicationsSafe(): List<ApplicationInfo> {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
        }.getOrDefault(emptyList())
    }

    private fun getApplicationInfoOrNull(packageName: String): ApplicationInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
        }.getOrNull()
    }

    private fun isSystemAppInfo(appInfo: ApplicationInfo): Boolean =
        (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    private fun resolveAppLabel(appInfo: ApplicationInfo?, fallbackPackage: String): String {
        if (appInfo == null) return fallbackPackage
        return runCatching { packageManager.getApplicationLabel(appInfo).toString().trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackPackage
    }

    internal fun scoreMatch(app: InstalledAppInfo, query: String): Int =
        scoreRecord(toRecord(app), query)

    private fun scoreRecord(record: AppInventoryRecord, query: String): Int {
        val clean = collapseWhitespace(query)
        val q = clean.lowercase()
        if (q.isEmpty()) return 0

        if (isExcludedSelf(record.info.packageName, q)) return 0

        val nameLower = record.normalizedDisplayName
        val pkgLower = record.normalizedPackageName
        val normalizedQ = compactNormalize(q)
        val normalizedName = record.compactNormalizedName
        val words = nameLower.split(' ', '-', '_', '.').filter { it.isNotBlank() }
        val qWords = q.split(' ', '-', '_').filter { it.isNotBlank() }

        return when {
            pkgLower == q -> 100
            nameLower == q -> 95
            normalizedQ.isNotEmpty() && normalizedName == normalizedQ -> 92
            qWords.size > 1 && words.containsAll(qWords) && nameLower.startsWith(q) -> 85
            nameLower.startsWith("$q ") -> 78
            words.any { it == q } -> 76
            nameLower.startsWith(q) -> 72
            normalizedQ.isNotEmpty() && normalizedName.startsWith(normalizedQ) -> 68
            nameLower.contains(q) -> 60
            pkgLower.endsWith(".$q") -> 55
            pkgLower.contains(q) -> 45
            else -> 0
        }
    }

    private fun isExcludedSelf(packageName: String, queryLower: String): Boolean =
        packageName == context.packageName &&
            !queryLower.contains("nexa") &&
            queryLower != packageName.lowercase()

    private fun collapseWhitespace(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")

    private fun compactNormalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    companion object {
        const val DEFAULT_LIST_LIMIT = 50
        const val MAX_LIST_LIMIT = 150
    }
}
