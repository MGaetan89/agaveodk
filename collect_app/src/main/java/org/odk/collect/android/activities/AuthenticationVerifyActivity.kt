package org.odk.collect.android.activities

import android.os.Bundle
import android.os.Handler
import org.odk.collect.android.R
import org.odk.collect.strings.localization.LocalizedActivity

class AuthenticationVerifyActivity : LocalizedActivity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.authentication_verify_layout)

        val updateHandler = Handler()

        val runnable = Runnable {
            openMainMenu()
        }

        updateHandler.postDelayed(runnable, 1000)

    }
    private fun openMainMenu() {
        ActivityUtils.startActivityAndCloseAllOthers(
            this@AuthenticationVerifyActivity,
            MainMenuActivity::class.java
        )
    }
}