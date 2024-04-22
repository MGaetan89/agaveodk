package org.odk.collect.util;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import org.odk.collect.NetworkMapManager;
import org.odk.collect.listeners.NetworkDataSaveTaskListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

public class NetworkDataSaveTask extends AsyncTask<File, Void, NetworkMapManager.NetworkFile> {
    private final NetworkMapManager.State state;
    private final Context context;
    private final Bundle bundle;
    private final List<NetworkDataSaveTaskListener> listeners;
    public NetworkDataSaveTask(NetworkDataSaveTaskListener listener, Bundle bundle, NetworkMapManager.State state,
                               Context context) {
        this.listeners = new ArrayList<>();
        listeners.add(listener);
        this.bundle = bundle;
        this.state = state;
        this.context = context;
    }

    @Override
    protected NetworkMapManager.NetworkFile doInBackground(File... params) {
        File instanceFile = params[0];

        final Object[] fileFilename = new Object[2];
        try (final OutputStream fos = FileAccess.getOutputStream( context, bundle, fileFilename )) {
            final File file = (File) fileFilename[0];
            final String filename = (String) fileFilename[1];
            // write file
            FileUtility.writeFile(context, state.dbHelper, fos, bundle);
            final boolean hasSD = FileUtility.hasSD();
            final String absolutePath = hasSD ? file.getAbsolutePath() : context.getFileStreamPath(filename).getAbsolutePath();

            Timber.i("filepath: " + absolutePath);
            File gz = new File(absolutePath);
            File externalFile = new File(instanceFile.getParent()
                    + File.separator
                    + System.currentTimeMillis()
                    + ".csv.gz");
            FileUtility.copyFile(gz, externalFile);

            return new NetworkMapManager.NetworkFile(externalFile.getAbsolutePath());
        } catch (Exception e) {
            Timber.e(e,"Exception");
            throw new RuntimeException(e);
        }
    }

    public void addListener(NetworkDataSaveTaskListener listener) {
        synchronized (this) {
            listeners.add(listener);
        }
    }
    public void removeListener(NetworkDataSaveTaskListener listener) {
        synchronized (this) {
            listeners.remove(listener);
        }
    }
    @Override
    protected void onPostExecute(NetworkMapManager.NetworkFile networkFile) {
        synchronized (this) {
            for (NetworkDataSaveTaskListener i: listeners) {
                i.onFileWritten(networkFile);
            }
        }
    }

}
