package com.example.android.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface ApprovalAuthResult {
    data object Verified : ApprovalAuthResult
    data class ProceedWithWarning(val warningMessage: String) : ApprovalAuthResult
    data class Cancelled(val reason: String) : ApprovalAuthResult
    data class Failed(val errorMessage: String) : ApprovalAuthResult
}

interface ApprovalAuthenticator {
    fun canAuthenticate(): Boolean
    suspend fun authenticate(
        activity: Activity?,
        title: String,
        subtitle: String,
        onDeviceCredentialIntentRequired: ((Intent) -> Unit)? = null
    ): ApprovalAuthResult
}

class AndroidApprovalAuthenticator(
    private val context: Context
) : ApprovalAuthenticator {

    override fun canAuthenticate(): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isDeviceSecure = km?.isDeviceSecure == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bm = context.getSystemService(BiometricManager::class.java)
            val status = bm?.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) ?: BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
            return status == BiometricManager.BIOMETRIC_SUCCESS || isDeviceSecure
        }
        return isDeviceSecure
    }

    override suspend fun authenticate(
        activity: Activity?,
        title: String,
        subtitle: String,
        onDeviceCredentialIntentRequired: ((Intent) -> Unit)?
    ): ApprovalAuthResult {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isDeviceSecure = km?.isDeviceSecure == true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bm = context.getSystemService(BiometricManager::class.java)
                val status = bm?.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                ) ?: BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

                if (status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED && !isDeviceSecure) {
                    return ApprovalAuthResult.ProceedWithWarning(
                        "No device lock screen or biometric credential is enrolled. Approval proceeded without lock verification."
                    )
                }
                if (status != BiometricManager.BIOMETRIC_SUCCESS && !isDeviceSecure) {
                    return ApprovalAuthResult.ProceedWithWarning(
                        "Biometric or device credential hardware unavailable on this device. Approval proceeded with notice."
                    )
                }
            } else if (!isDeviceSecure) {
                return ApprovalAuthResult.ProceedWithWarning(
                    "No device lock screen is enrolled. Approval proceeded without lock verification."
                )
            }

            if (activity == null) {
                return ApprovalAuthResult.ProceedWithWarning(
                    "No foreground Activity host available for biometric prompt. Proceeding with notice."
                )
            }

            return suspendCancellableCoroutine { cont ->
                val cancellationSignal = CancellationSignal()
                cont.invokeOnCancellation { cancellationSignal.cancel() }

                val builder = BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setSubtitle(subtitle)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    builder.setDeviceCredentialAllowed(true)
                }

                val prompt = builder.build()
                val executor = ContextCompat.getMainExecutor(activity)
                runCatching {
                    prompt.authenticate(
                        cancellationSignal,
                        executor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                                if (cont.isActive) cont.resume(ApprovalAuthResult.Verified)
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                                if (!cont.isActive) return
                                val msg = errString?.toString()?.ifBlank { null } ?: "Authentication error ($errorCode)"
                                when (errorCode) {
                                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                                    BiometricPrompt.BIOMETRIC_ERROR_CANCELED ->
                                        cont.resume(ApprovalAuthResult.Cancelled(msg))
                                    BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
                                    BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL,
                                    BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
                                    BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                                        cont.resume(
                                            ApprovalAuthResult.ProceedWithWarning(
                                                "No biometric or device lock credential is enrolled ($msg). Approval proceeded with notice."
                                            )
                                        )
                                    else -> cont.resume(ApprovalAuthResult.Failed(msg))
                                }
                            }

                            override fun onAuthenticationFailed() {
                                // Prompt remains open for retry until error/cancel/success.
                            }
                        }
                    )
                }.onFailure { err ->
                    if (cont.isActive) {
                        cont.resume(
                            ApprovalAuthResult.ProceedWithWarning(
                                "Biometric prompt unavailable (${err.message ?: "unsupported"}). Proceeding with notice."
                            )
                        )
                    }
                }
            }
        }

        // API 24-28 fallback: KeyguardManager device credential confirmation
        if (!isDeviceSecure) {
            return ApprovalAuthResult.ProceedWithWarning(
                "No device lock screen (PIN, pattern, or password) is enrolled. Approval proceeded without lock verification."
            )
        }
        @Suppress("DEPRECATION")
        val confirmIntent = km?.createConfirmDeviceCredentialIntent(title, subtitle)
        return if (confirmIntent != null && onDeviceCredentialIntentRequired != null) {
            onDeviceCredentialIntentRequired(confirmIntent)
            // Caller completes confirmation via ActivityResultLauncher callback.
            ApprovalAuthResult.Cancelled("Awaiting device credential verification screen.")
        } else {
            ApprovalAuthResult.ProceedWithWarning(
                "Device credential prompt unavailable on this API level. Approval proceeded with notice."
            )
        }
    }
}
