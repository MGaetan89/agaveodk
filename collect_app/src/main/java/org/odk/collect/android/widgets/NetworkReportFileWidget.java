/*
 * Copyright 2018 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.odk.collect.android.widgets;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import java.io.File;

import org.javarosa.form.api.FormEntryPrompt;
import org.odk.collect.NetworkMapManager;
import org.odk.collect.android.databinding.ArbitraryFileWidgetAnswerBinding;
import org.odk.collect.android.formentry.questions.QuestionDetails;
import org.odk.collect.android.listeners.FilePickedListener;
import org.odk.collect.android.utilities.ApplicationConstants;
import org.odk.collect.android.utilities.QuestionMediaManager;
import org.odk.collect.android.widgets.interfaces.FileWidget;
import org.odk.collect.android.widgets.interfaces.WidgetDataReceiver;
import org.odk.collect.android.widgets.utilities.WaitingForDataRegistry;

import timber.log.Timber;

@SuppressLint("ViewConstructor")
public class NetworkReportFileWidget extends ArbitraryFileWidget implements FileWidget, WidgetDataReceiver {

    private FilePickedListener listener;
    NetworkReportFileWidget(Context context, QuestionDetails questionDetails,
                            QuestionMediaManager questionMediaManager, WaitingForDataRegistry waitingForDataRegistry) {
        super(context, questionDetails, questionMediaManager, waitingForDataRegistry);
    }

    @Override
    protected View onCreateAnswerView(Context context, FormEntryPrompt prompt, int answerFontSize) {
        View view = super.onCreateAnswerView(context, prompt, answerFontSize);
        return view;
    }

    public void setListener(FilePickedListener listener) {
        this.listener = listener;
    }
    @Override
    protected void setupAnswerFile(String fileName) {
        super.setupAnswerFile(fileName);
        validateAnswerFile();
    }

    public void validateAnswerFile() {
        if(getAnswer() == null) {
            Timber.i("prefill metadata");
//            try {
//                NetworkMapManager.NetworkFile networkReport = NetworkMapManager.getManager().writeFile(new Bundle());
//                File answer = new File(networkReport.getPath());
//                super.waitingForDataRegistry.waitForData(getFormEntryPrompt().getIndex());
//                listener.onFilePicked(Uri.fromFile(answer));
//            } catch (Exception e) {
//                Timber.e(e);
//            }
        }
    }
}
