// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.content.Context;
import android.content.SharedPreferences;
import de.wfabia.core.Modules;

/** Module switches in one preferences file; changes apply at runtime. */
final class PrefsStore implements Modules.Store {
    private final SharedPreferences p;

    PrefsStore(Context c) { p = c.getSharedPreferences("wfabia", Context.MODE_PRIVATE); }

    @Override public boolean getBool(String key, boolean dflt) { return p.getBoolean(key, dflt); }

    @Override public void putBool(String key, boolean value) { p.edit().putBoolean(key, value).apply(); }

    @Override public String getString(String key, String dflt) { return p.getString(key, dflt); }

    @Override public void putString(String key, String value) { p.edit().putString(key, value).apply(); }
}
