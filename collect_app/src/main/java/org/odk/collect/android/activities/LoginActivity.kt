package org.odk.collect.android.activities

import android.os.Bundle
import android.widget.Button
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.odk.collect.android.R
import org.odk.collect.strings.localization.LocalizedActivity
import timber.log.Timber
import java.util.concurrent.Executor

class LoginActivity : LocalizedActivity() {

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    val BIOMETRIC_STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.login_activity_layout)

        executor = ContextCompat.getMainExecutor(this)

        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationError(errorCode: Int,
                                                   errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Timber.d("TAG: Authentication Error "+errorCode);
                    if(errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        loginWithPassword()
                    }
                }

                override fun onAuthenticationSucceeded (
                    result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Timber.d("TAG: success: ");
                    ActivityUtils.startActivityAndCloseAllOthers(
                        this@LoginActivity,
                        AuthenticationVerifyActivity::class.java
                    )
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Timber.d("TAG:  Authentication Failed");
                    biometricPrompt.cancelAuthentication()
                    ActivityUtils.startActivityAndCloseAllOthers(
                        this@LoginActivity,
                        ErrorAccessActivity::class.java
                    )
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric login")
            .setSubtitle("Log in using your biometric credential")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText("Use pin")
            .build()

        val canAuthenticate = BiometricManager.from(applicationContext).canAuthenticate(BIOMETRIC_STRONG)

        Timber.i("TAG: canAuthenticate: $canAuthenticate")

        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            val biometricLoginButton =
                findViewById<Button>(R.id.biometric_login)
            biometricLoginButton.setOnClickListener {
                    biometricPrompt.authenticate(promptInfo)
            }
        } else {
            loginWithPassword()
        }
    }

    private fun loginWithPassword() {
        Timber.d("********* use Pin: ");
        ActivityUtils.startActivityAndCloseAllOthers(
            this@LoginActivity,
            PinAuthenticationActivity::class.java
        )
    }

}