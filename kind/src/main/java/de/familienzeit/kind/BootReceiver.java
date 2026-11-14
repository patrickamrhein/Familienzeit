package de.familienzeit.kind;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startet die Überwachung nach dem Einschalten und nach jedem Update neu. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Policy.isOwner(context) && !Store.code(context).isEmpty()) {
            GuardService.start(context);
        }
    }
}
