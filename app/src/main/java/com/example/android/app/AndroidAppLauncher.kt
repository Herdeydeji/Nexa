package com.example.android.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.example.android.runtime.AndroidActionVerifier
import com.example.android.runtime.AndroidPermissionManager
import com.example.domain.model.AppLaunchOutcome
import com.example.domain.model.AppLaunchStatus
import com.example.domain.model.AppLookupOutcome

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
                if (verification.verified) {
                    AppLaunchOutcome(
                        success = true,
                        appName = target.appName,
                        packageName = target.packageName,
                        launchIntentDispatched = true,
                        foregroundVerified = true,
                        launchStatus = AppLaunchStatus.FOREGROUND_VERIFIED,
                        observedForegroundPackage = verification.observedPackage,
                        message = "Opened ${target.appName} (${target.packageName}) and verified it is now in the foreground."
                    )
                } else {
                    AppLaunchOutcome(
                        success = true,
                        appName = target.appName,
                        packageName = target.packageName,
                        launchIntentDispatched = true,
                        foregroundVerified = false,
                        launchStatus = AppLaunchStatus.LAUNCH_DISPATCHED,
                        observedForegroundPackage = verification.observedPackage,
                        message = "Dispatched launcher intent for ${target.appName} (${target.packageName}), but foreground package observed was '${verification.observedPackage ?: "unknown"}'. The app may still be loading or another dialog is active."
                    )
                }
            }
        }
    }
}
