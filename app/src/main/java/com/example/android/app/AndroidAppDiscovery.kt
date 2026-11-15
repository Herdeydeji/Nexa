package com.example.android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.example.domain.model.AppLookupOutcome
import com.example.domain.model.InstalledAppInfo

data class AppDiscoveryBatch(
    val apps: List<InstalledAppInfo>,
    val totalDiscovered: Int,
    val visibilityNotice: String
)

open class AndroidAppDiscovery(
    private val context: Context,
    private val packageManager: PackageManager = context.packageManager
) {

    open fun listLaunchableApps(
        query: String? = null,
        includeSystemApps: Boolean = true,
        limit: Int = DEFAULT_LIST_LIMIT
    ): AppDiscoveryBatch = listInstalledApps(
        query = query,
        includeSystemApps = includeSystemApps,
        includeNonLaunchable = false,
        limit = limit
    )

    open fun listInstalledApps(
        query: String? = null,
        includeSystemApps: Boolean = true,
        includeNonLaunchable: Boolean = false,
        limit: Int = DEFAULT_LIST_LIMIT
    ): AppDiscoveryBatch {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = queryLauncherActivities(launcherIntent)
        val deduped = linkedMapOf<String, InstalledAppInfo>()

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

            deduped[pkg] = InstalledAppInfo(
                appName = label,
                packageName = pkg,
                launcherActivity = actInfo.name,
                isLaunchable = true,
                isSystemApp = isSystem
            )
        }

        // Also inspect PackageManager.getInstalledApplications() for Android-visible packages
        // (respecting package visibility rules declared in AndroidManifest.xml without QUERY_ALL_PACKAGES)
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
            deduped[pkg] = InstalledAppInfo(
                appName = label,
                packageName = pkg,
                launcherActivity = launchIntent?.component?.className,
                isLaunchable = launchable,
                isSystemApp = isSystem
            )
        }

        val allApps = deduped.values.sortedBy { it.appName.lowercase() }
        val cleanQuery = query?.trim().orEmpty()
        val filtered = if (cleanQuery.isBlank()) {
            allApps
        } else {
            allApps
                .mapNotNull { app ->
                    val score = scoreMatch(app, cleanQuery)
                    if (score > 0) score to app else null
                }
                .sortedWith(compareByDescending<Pair<Int, InstalledAppInfo>> { it.first }
                    .thenBy { it.second.appName.lowercase() })
                .map { it.second }
        }

        val boundedLimit = limit.coerceIn(1, MAX_LIST_LIMIT)
        return AppDiscoveryBatch(
            apps = filtered.take(boundedLimit),
            totalDiscovered = filtered.size,
            visibilityNotice = if (includeNonLaunchable) {
                "Queried visible installed applications and launcher intents (Android package visibility respected)."
            } else {
                "Queried launchable applications from Android launcher intents and visible installed packages (Android package visibility respected)."
            }
        )
    }

    open fun findApp(query: String): AppLookupOutcome {
        val clean = query.trim()
        if (clean.isEmpty()) {
            return AppLookupOutcome.NotFound(
                query = query,
                visibilityRestricted = false,
                reason = "Provide a non-empty application name or package identifier."
            )
        }

        val allVisible = listInstalledApps(
            includeSystemApps = true,
            includeNonLaunchable = true,
            limit = MAX_LIST_LIMIT
        ).apps
        val scoredLaunchable = allVisible
            .filter { it.isLaunchable }
            .mapNotNull { app ->
                val score = scoreMatch(app, clean)
                if (score > 0) score to app else null
            }
            .sortedWith(compareByDescending<Pair<Int, InstalledAppInfo>> { it.first }
                .thenBy { it.second.appName.length })

        if (scoredLaunchable.isNotEmpty()) {
            val best = scoredLaunchable.first().second
            val alternatives = scoredLaunchable.drop(1).take(4).map { it.second }
            return AppLookupOutcome.Found(app = best, alternatives = alternatives)
        }

        val scoredNonLaunchable = allVisible
            .filter { !it.isLaunchable }
            .mapNotNull { app ->
                val score = scoreMatch(app, clean)
                if (score > 0) score to app else null
            }
            .sortedWith(compareByDescending<Pair<Int, InstalledAppInfo>> { it.first }
                .thenBy { it.second.appName.length })

        if (scoredNonLaunchable.isNotEmpty()) {
            val bestNonLaunchable = scoredNonLaunchable.first().second
            return AppLookupOutcome.NotLaunchable(
                appName = bestNonLaunchable.appName,
                packageName = bestNonLaunchable.packageName,
                reason = "Package '${bestNonLaunchable.packageName}' (${bestNonLaunchable.appName}) is installed, but exposes no launchable main activity."
            )
        }

        // Check if query looks like a visible package name that exists via direct getApplicationInfo
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

    private fun queryLauncherActivities(intent: Intent): List<ResolveInfo> {
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

    internal fun scoreMatch(app: InstalledAppInfo, query: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val nameLower = app.appName.lowercase()
        val pkgLower = app.packageName.lowercase()
        val normalizedQ = normalize(q)
        val normalizedName = normalize(nameLower)

        // Avoid accidentally matching Nexa itself unless user specifically asks for Nexa
        val isSelf = app.packageName == context.packageName
        if (isSelf && !q.contains("nexa") && q != pkgLower) return 0

        return when {
            pkgLower == q -> 100
            nameLower == q -> 95
            normalizedQ.isNotEmpty() && normalizedName == normalizedQ -> 90
            nameLower.startsWith(q) -> 80
            normalizedQ.isNotEmpty() && normalizedName.startsWith(normalizedQ) -> 75
            nameLower.split(' ', '-', '_').any { it == q } -> 72
            nameLower.contains(q) -> 65
            pkgLower.endsWith(".$q") -> 62
            pkgLower.contains(q) -> 50
            else -> 0
        }
    }

    private fun normalize(value: String): String =
        value.replace(Regex("[^a-z0-9]"), "")

    companion object {
        const val DEFAULT_LIST_LIMIT = 50
        const val MAX_LIST_LIMIT = 150
    }
}
