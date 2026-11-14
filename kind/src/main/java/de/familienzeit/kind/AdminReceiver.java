package de.familienzeit.kind;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Wird aktiv, sobald die App per PC zum Geräteeigentümer gemacht wurde. */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        // Die eigentlichen Sperren greifen erst nach der Kopplung mit der Eltern-App,
        // damit die Einrichtung per USB vorher sauber abgeschlossen werden kann.
    }
}
