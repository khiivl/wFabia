// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.core.content.ContextCompat;

/**
 * Launched by the system when the CANable is plugged in: starts the service and disappears. It lives in a task of its
 * own (taskAffinity="" in the manifest) and removes that task when it is done, so it never pulls wFabia's main screen
 * to the front.
 */
public class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ContextCompat.startForegroundService(this, new Intent(this, FabiaService.class));
        finishAndRemoveTask();
    }
}
