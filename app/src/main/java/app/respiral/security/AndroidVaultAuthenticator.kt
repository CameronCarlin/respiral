package app.respiral.security

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface AuthenticationResult {
    data object Success : AuthenticationResult

    data object Cancelled : AuthenticationResult

    data object Unavailable : AuthenticationResult
}

interface VaultAuthenticator {
    suspend fun authenticate(activity: FragmentActivity): AuthenticationResult
}

/** Uses Android's system prompt; no credential or biometric material enters the app. */
class AndroidVaultAuthenticator : VaultAuthenticator {
    override suspend fun authenticate(activity: FragmentActivity): AuthenticationResult {
        if (!canAuthenticate(activity)) return AuthenticationResult.Unavailable

        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(AuthenticationResult.Success)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) {
                            continuation.resume(errorCode.toAuthenticationResult())
                        }
                    }
                },
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }

            try {
                prompt.authenticate(promptInfo())
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(AuthenticationResult.Unavailable)
            }
        }
    }

    private fun canAuthenticate(activity: FragmentActivity): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
            BiometricManager.from(activity).canAuthenticate(allowedAuthenticators()) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }
        else -> true // API 29 supports passcode fallback only through the compatible prompt configuration.
    }

    private fun promptInfo(): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Respiral")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(allowedAuthenticators())
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        return builder.build()
    }

    private fun allowedAuthenticators(): Int =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    private fun Int.toAuthenticationResult(): AuthenticationResult = when (this) {
        BiometricPrompt.ERROR_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_USER_CANCELED,
        -> AuthenticationResult.Cancelled
        else -> AuthenticationResult.Unavailable
    }
}

/** Clears a session immediately after Android reports that the device itself is locked. */
class DeviceLockVaultSessionObserver(
    private val keyguardManager: KeyguardManager,
    private val session: VaultSession,
) : DefaultLifecycleObserver {
    override fun onResume(owner: LifecycleOwner) {
        if (keyguardManager.isDeviceLocked) session.lock()
    }
}
