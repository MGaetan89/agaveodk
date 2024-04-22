package org.odk.collect.listeners;

import org.odk.collect.NetworkMapManager;

public interface NetworkDataSaveTaskListener {
    void onFileWritten(NetworkMapManager.NetworkFile file);
}
