package com.example.android.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.example.android.runtime.AndroidActionVerifier
import com.example.android.runtime.AndroidPermissionManager
import com.example.android.runtime.ForegroundBlockReason
import com.example.domain.model.AppLaunchOutcome
import com.example.domain.model.AppLaunchStatus
import com.example.domain.model.AppLookupOutcome
import com.example.domain.model.InstalledAppInfo

open class AndroidAppLauncher(
    private val context: Context,
    private val appDiscovery: AndroidAppDiscovery,
    private val permissionManager: AndroidPermissionManager,
    private val actionVerifier: AndroidActionVerifier,
    private val activityStarter: (Intent) -> Unit = { intent -> context.startActivity(intent) }
) {

    open suspend fun launch(appQuery: String): AppLaunchOutcome {
        val clean = appQuery.trim()
        if (clean.isEmpty()) {
            return AppLaunchOutcome(
                success = false,
                appName = null,
                packageName = "",
                launchIntentDispatched = false,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.NOT_FOUND,
                message = "Missing application name or package name to launch.",
                errorCode = "INVALID_ARGUMENT"
            )
        }

        return when (val lookup = appDiscovery.findApp(clean)) {
            is AppLookupOutcome.NotFound -> AppLaunchOutcome(
                success = false,
                appName = null,
                packageName = if (clean.contains('.')) clean else "",
                launchIntentDispatched = false,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.NOT_FOUND,
                message = lookup.reason,
                errorCode = "APP_NOT_FOUND"
            )

            is AppLookupOutcome.NotLaunchable -> AppLaunchOutcome(
                success = false,
                appName = lookup.appName,
                packageName = lookup.packageName,
                launchIntentDispatched = false,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.NOT_LAUNCHABLE,
                message = lookup.reason,
                errorCode = "APP_NOT_LAUNCHABLE"
            )

            is AppLookupOutcome.Ambiguous -> AppLaunchOutcome(
                success = false,
                appName = null,
                packageName = "",
                launchIntentDispatched = false,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.NOT_FOUND,
                message = lookup.reason,
                errorCode = "APP_AMBIGUOUS",
                requiresUserAction = true,
                recoveryAction = "SELECT_AMBIGUOUS_APP",
                blockerReason = lookup.reason,
                userTitle = "Choose application",
                userMessage = "Multiple installed apps match '$clean'. Please choose which app to open.",
                ambiguousCandidates = lookup.candidates
            )

            is AppLookupOutcome.Found -> {
                val target = lookup.app
                val intent = appDiscovery.resolveLaunchIntent(target.packageName, target.launcherActivity)
                    ?: return AppLaunchOutcome(
                        success = false,
                        appName = target.appName,
                        packageName = target.packageName,
                        launchIntentDispatched = false,
                        foregroundVerified = false,
                        launchStatus = AppLaunchStatus.NOT_LAUNCHABLE,
                        message = "Found ${target.appName} (${target.packageName}), but Android returned no launchable intent.",
                        errorCode = "NO_LAUNCH_INTENT"
                    )

                dispatchAndVerifyTarget(target, intent)
            }
        }
    }

    open suspend fun launchResolvedApp(target: InstalledAppInfo, customIntent: Intent? = null): AppLaunchOutcome {
        val intent = customIntent ?: appDiscovery.resolveLaunchIntent(target.packageName, target.launcherActivity)
            ?: return AppLaunchOutcome(
                success = false,
                appName = target.appName,
                packageName = target.packageName,
                launchIntentDispatched = false,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.NOT_LAUNCHABLE,
                message = "Found ${target.appName} (${target.packageName}), but Android returned no launchable intent.",
                errorCode = "NO_LAUNCH_INTENT"
            )
        return dispatchAndVerifyTarget(target, intent)
    }

    private suspend fun dispatchAndVerifyTarget(target: InstalledAppInfo, intent: Intent): AppLaunchOutcome {
        val dispatchError = try {
            activityStarter(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "Android could not find an activity to launch ${target.appName} (${target.packageName}): ${e.message}"
        } catch (e: SecurityException) {
            "Android security policy blocked launching ${target.appName} (${target.packageName}): ${e.message}"
        } catch (t: Throwable) {
            "Failed to launch ${target.appName} (${target.packageName}): ${t.message}"
        }

        if (dispatchError != null) {
            return AppLaunchOutcome(
                success = false,
                appName = target.appName,
                packageName = target.packageName,
                launchIntentDispatched = false,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.LAUNCH_FAILED,
                message = dispatchError,
                errorCode = "LAUNCH_BLOCKED"
            )
        }

        if (!permissionManager.isAccessibilityReady()) {
            return AppLaunchOutcome(
                success = true,
                appName = target.appName,
                packageName = target.packageName,
                launchIntentDispatched = true,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.LAUNCH_DISPATCHED,
                observedForegroundPackage = null,
                message = "Dispatched Android launcher intent for ${target.appName} (${target.packageName}). Foreground window verification is unavailable because Nexa Accessibility Service is disabled."
            )
        }

        val verification = actionVerifier.verifyForegroundPackage(target.packageName)
        return when (verification.blockReason) {
            ForegroundBlockReason.NONE -> AppLaunchOutcome(
                success = true,
                appName = target.appName,
                packageName = target.packageName,
                launchIntentDispatched = true,
                foregroundVerified = true,
                launchStatus = AppLaunchStatus.FOREGROUND_VERIFIED,
                observedForegroundPackage = verification.observedPackage,
                message = "Opened ${target.appName} (${target.packageName}) and verified it is now in the foreground."
            )

            ForegroundBlockReason.APPLOCK -> AppLaunchOutcome(
                success = false,
                appName = target.appName,
                packageName = target.packageName,
                launchIntentDispatched = true,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.BLOCKED_BY_APPLOCK,
                observedForegroundPackage = verification.observedPackage,
                message = "The target application (${target.appName}) is protected by Transsion AppLock.",
                errorCode = "BLOCKED_BY_APPLOCK",
                requiresUserAction = true,
                recoveryAction = "WAIT_FOR_USER_UNLOCK",
                blockerReason = "The target application is protected by Transsion AppLock.",
                userTitle = "Unlock ${target.appName}",
                userMessage = "Your device's AppLock is protecting this application. Unlock it on your phone. Nexa will continue automatically when access is available."
            )

            ForegroundBlockReason.SYSTEM_DIALOG -> AppLaunchOutcome(
                success = false,
                appName = target.appName,
                packageName = target.packageName,
                launchIntentDispatched = true,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.BLOCKED_BY_DIALOG,
                observedForegroundPackage = verification.observedPackage,
                message = "Launch of ${target.appName} (${target.packageName}) is blocked by system dialog '${verification.observedPackage ?: "unknown"}'.",
                errorCode = "BLOCKED_BY_DIALOG",
                requiresUserAction = true,
                recoveryAction = "RESOLVE_BLOCKING_DIALOG",
                blockerReason = verification.detail,
                userTitle = "Action needed on screen",
                userMessage = "A system dialog (${verification.observedPackage ?: "dialog"}) appeared instead of ${target.appName}. Respond to it on your phone."
            )

            ForegroundBlockReason.LOADING -> AppLaunchOutcome(
                success = false,
                appName = target.appName,
                packageName = target.packageName,
                launchIntentDispatched = true,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.APP_LOADING,
                observedForegroundPackage = verification.observedPackage,
                message = "Dispatched launch for ${target.appName} (${target.packageName}), but the app has not reached foreground yet (observed '${verification.observedPackage ?: "unknown"}').",
                errorCode = "APP_LOADING",
                blockerReason = verification.detail
            )

            ForegroundBlockReason.OTHER_APP,
            ForegroundBlockReason.UNAVAILABLE -> AppLaunchOutcome(
                success = false,
                appName = target.appName,
                packageName = target.packageName,
                launchIntentDispatched = true,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.LAUNCH_DISPATCHED,
                observedForegroundPackage = verification.observedPackage,
                message = "Dispatched launch for ${target.appName} (${target.packageName}), but foreground remained '${verification.observedPackage ?: "unknown"}' rather than the target app.",
                errorCode = "FOREGROUND_MISMATCH",
                blockerReason = verification.detail
            )
        }
    }
}
