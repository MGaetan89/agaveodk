package org.odk.collect.ui;

import android.app.Activity;
import androidx.fragment.app.FragmentActivity;

/**
 * Display that magic toast
 * Created by arkasha on 9/21/17.
 */

public class WToast {

    public static void showOverFragment(final FragmentActivity context, final int titleId,
                                        final String messageString) {
        showOverActivity(context, titleId, messageString, android.widget.Toast.LENGTH_LONG);
    }

    public static void showOverActivity(final Activity context, final int titleId,
                                        final String messageString) {
        showOverActivity(context, titleId, messageString, android.widget.Toast.LENGTH_SHORT);
    }

    public static void showOverActivity(final Activity context, final int titleId,
        final String messageString, final int toastLength) {
        if (null != context && !context.isFinishing()) {
            android.widget.Toast toast = android.widget.Toast.makeText(context /* MyActivity */, messageString, toastLength);
            toast.show();
        }
    }

}
