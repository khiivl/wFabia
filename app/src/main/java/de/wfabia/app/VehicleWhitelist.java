// SPDX-License-Identifier: GPL-3.0-or-later
package de.wfabia.app;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import de.wfabia.core.Modules;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The apps that may use the vehicle pipe. Empty by default. An app is identified by its package name AND the SHA-256 digest of
 * its signing certificate, so an app that replaces an allowed package but is signed with another key gets nothing (the same
 * rule as the Open Headunit whitelist, docs/CONTRACT.md).
 */
final class VehicleWhitelist {
    static final String WRIGHT = "com.radiorubka.wright";
    private static final String KEY = "vehicle_allow";

    private final Modules.Store store;

    VehicleWhitelist(Modules.Store store) { this.store = store; }

    private Set<String> entries() {
        Set<String> out = new LinkedHashSet<>();
        for (String e : store.getString(KEY, "").split(";")) if (!e.isEmpty()) out.add(e);
        return out;
    }

    private void save(Set<String> e) { store.putString(KEY, String.join(";", e)); }

    /** is the package on the list (any certificate); for the UI */
    synchronized boolean isListed(String pkg) {
        for (String e : entries()) if (e.startsWith(pkg + "|")) return true;
        return false;
    }

    /** package and certificate both match an entry */
    synchronized boolean isAllowed(PackageManager pm, String pkg) {
        String digest = certDigest(pm, pkg);
        return digest != null && entries().contains(pkg + "|" + digest);
    }

    /** puts the installed package on the list with its current certificate; false if it is not installed */
    synchronized boolean allow(PackageManager pm, String pkg) {
        String digest = certDigest(pm, pkg);
        if (digest == null) return false;
        Set<String> e = entries();
        e.removeIf(x -> x.startsWith(pkg + "|"));
        e.add(pkg + "|" + digest);
        save(e);
        return true;
    }

    synchronized void revoke(String pkg) {
        Set<String> e = entries();
        e.removeIf(x -> x.startsWith(pkg + "|"));
        save(e);
    }

    static String certDigest(PackageManager pm, String pkg) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo si = pi.signingInfo;
            if (si == null) return null;
            Signature[] sigs = si.getApkContentsSigners();
            if (sigs == null || sigs.length == 0) return null;
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (PackageManager.NameNotFoundException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }
}
