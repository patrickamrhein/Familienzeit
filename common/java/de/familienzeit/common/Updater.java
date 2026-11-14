package de.familienzeit.common;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Lädt neue Versionen aus den GitHub-Releases des Repositories und installiert sie.
 * Als Geräteeigentümer (Kinder-App) passiert das ohne Rückfrage, in der Eltern-App
 * bestätigt man die Installation einmal.
 */
public final class Updater {
    private Updater() {}

    public static String base(String repo) {
        return "https://github.com/" + repo + "/releases/latest/download/";
    }

    /** Neueste verfügbare Versionsnummer, oder -1 wenn unbekannt. */
    public static int remoteVersion(String repo) {
        if (repo == null || repo.isEmpty()) return -1;
        try {
            String s = new String(Ntfy.get(base(repo) + "version.txt"), StandardCharsets.UTF_8).trim();
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Lädt {@code apkName} herunter, prüft Paketname und Version und startet die Installation.
     * @return Fehlermeldung oder null bei Erfolg (Installation angestoßen).
     */
    public static String downloadAndInstall(Context ctx, String repo, String apkName, int minVersion,
                                            Class<?> statusReceiver) {
        try {
            byte[] apk = Ntfy.get(base(repo) + apkName);
            File f = new File(ctx.getCacheDir(), "update.apk");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(apk);
            }
            PackageManager pm = ctx.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (info == null) return "Update-Datei ist beschädigt";
            if (!ctx.getPackageName().equals(info.packageName)) return "Update gehört zu einer anderen App";
            if (info.getLongVersionCode() < minVersion) return "Update ist nicht neuer";

            PackageInstaller pi = pm.getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(ctx.getPackageName());
            p.setSize(f.length());
            if (Build.VERSION.SDK_INT >= 31) {
                p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int id = pi.createSession(p);
            PackageInstaller.Session s = pi.openSession(id);
            try {
                try (OutputStream out = s.openWrite("update.apk", 0, f.length());
                     InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    s.fsync(out);
                }
                Intent i = new Intent(ctx, statusReceiver).setAction("de.familienzeit.INSTALL_STATUS");
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
                PendingIntent pend = PendingIntent.getBroadcast(ctx, id, i, flags);
                s.commit(pend.getIntentSender());
            } finally {
                s.close();
            }
            return null;
        } catch (Exception e) {
            return "Update fehlgeschlagen: " + e.getMessage();
        }
    }
}
