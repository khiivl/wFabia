// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.app.Application;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Writes any uncaught exception to a file the main screen shows on the next start, so a crash needs no adb. */
public class App extends Application {
    static File crashFile(android.content.Context c) { return new File(c.getFilesDir(), "last_crash.txt"); }

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileWriter w = new FileWriter(crashFile(this))) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                w.write("thread " + t.getName() + " at " + new java.util.Date() + "\n" + sw);
            } catch (IOException ignored) {
                // nothing more we can do
            }
            if (previous != null) previous.uncaughtException(t, e);
        });
    }
}
