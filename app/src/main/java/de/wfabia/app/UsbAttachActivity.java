// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.core.content.ContextCompat;

/** Launched by the system when the CANable is plugged in: starts the service and disappears. */
public class UsbAttachActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ContextCompat.startForegroundService(this, new Intent(this, FabiaService.class));
        finish();
    }
}
