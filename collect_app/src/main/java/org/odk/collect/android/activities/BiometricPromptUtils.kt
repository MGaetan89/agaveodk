/*package org.odk.collect.android.activities

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import timber.log.Timber

object BiometricPromptUtils {
    private const val TAG = "BioMetricPromptUtils"
    fun createBiometricPrompt(
        activity: AppCompatActivity,
        processSuccess: () -> Unit,
        processFail: () -> Unit,
        processError: (string: String) -> Unit
    ): BiometricPrompt {
        val executor = ContextCompat.getMainExecutor(activity)

        val callback = object : BiometricPrompt.AuthenticationCallback() {

            override fun onAuthenticationError(errCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errCode, errString)
                processError( "errCode is $errCode and errString is: $errString")
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Timber.d(TAG, "User biometric rejected.")
                processFail()
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Timber.d(TAG, "Authentication was successful")
                processSuccess()
            }
        }
        return BiometricPrompt(activity, executor, callback)
    }

    fun createPromptInfo(activity: AppCompatActivity) : BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder().apply {
            setTitle("Biometric login for odk")
            setSubtitle("Log in using your biometric credential")
            setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL )
        }.build()
}*/