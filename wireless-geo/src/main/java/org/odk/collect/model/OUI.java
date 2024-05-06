package org.odk.collect.model;

import android.content.res.AssetManager;
import android.os.Build;

import androidx.annotation.RequiresApi;

import timber.log.Timber;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public final class OUI {
    private final Properties properties = new Properties();
    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    public OUI(final AssetManager assetManager) {
        try (InputStreamReader isr = new InputStreamReader(assetManager.open("oui.properties"), StandardCharsets.UTF_8.toString())) {
            properties.load(isr);
            Timber.i("oui load complete");
        }
        catch (final IOException ex) {
            Timber.e(ex, "exception loading oui: " + ex);
        }
    }

    public String getOui(final String partial) {
        return properties.getProperty(partial);
    }
}