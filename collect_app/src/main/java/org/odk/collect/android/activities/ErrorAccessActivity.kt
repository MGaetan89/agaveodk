package org.odk.collect.android.activities

import android.content.Intent
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.odk.collect.android.R
import org.odk.collect.strings.localization.LocalizedActivity


class ErrorAccessActivity : LocalizedActivity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.error_access_layout)

        MaterialAlertDialogBuilder(this)
            .setTitle("DeliFood isn't responding")
            .setMessage("Do you want to close it?")
            .setPositiveButton("Close") { _, _ -> returnLogin() }
            .setNegativeButton("Wait") { _, _ -> retry() }
            .create()
            .show()
    }

    private fun returnLogin() {
        startActivity(Intent(this@ErrorAccessActivity, LoginActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.push_right_in)
        finishAffinity()
    }

    private fun retry() {
        startActivity(Intent(this@ErrorAccessActivity, ErrorAccessActivity::class.java))
        overridePendingTransition(R.anim.fade_in, R.anim.push_right_in)
        finishAffinity()
    }

}