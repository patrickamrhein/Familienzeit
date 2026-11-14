package de.familienzeit.kind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Ergebnis eines stillen Updates. Bei Fehlern werden die Sperren wiederhergestellt. */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Store.p(context).edit().putString("lastError", "Update: " + status + " " + msg).apply();
            Policy.applyBase(context, Store.cfg(context).optBoolean("debug", false));
        }
    }
}
