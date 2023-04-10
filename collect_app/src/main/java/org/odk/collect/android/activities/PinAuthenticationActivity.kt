package org.odk.collect.android.activities

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.core.content.ContextCompat
import org.odk.collect.android.R
import org.odk.collect.strings.localization.LocalizedActivity
import timber.log.Timber


class PinAuthenticationActivity : LocalizedActivity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pin_authentication_layout)

        val pin = findViewById<EditText>(R.id.editTextPin)
        pin.addTextChangedListener(textWatcher)
    }

    private val textWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            if (s.toString().length == 4) {
                if ( s.toString() == "2468" ) {
                    changeColor(R.color.colorVerify)
                    ActivityUtils.startActivityAndCloseAllOthers(
                        this@PinAuthenticationActivity,
                        AuthenticationVerifyActivity::class.java
                    )
                } else {
                    changeColor(R.color.colorError)
                    ActivityUtils.startActivityAndCloseAllOthers(
                        this@PinAuthenticationActivity,
                        ErrorAccessActivity::class.java
                    )
                }
            } else {
                changeColor(R.color.colorError)
            }
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    private fun changeColor(colorTint: Int) {
        val pin = findViewById<EditText>(R.id.editTextPin)
        pin.backgroundTintList = ContextCompat.getColorStateList(this, colorTint)
    }
}