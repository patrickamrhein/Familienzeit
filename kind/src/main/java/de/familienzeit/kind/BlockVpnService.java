package de.familienzeit.kind;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.util.Set;

/**
 * Ein "VPN", das keine Verbindung aufbaut, sondern allen Datenverkehr verwirft.
 * So ist das Internet gesperrt, während Telefon und SMS weiter funktionieren.
 * Apps auf der "immer erlaubt"-Liste werden vorbeigeleitet.
 */
public class BlockVpnService extends VpnService {
    static final String STOP = "de.familienzeit.kind.VPN_STOP";

    private ParcelFileDescriptor tun;
    private Thread drain;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && STOP.equals(intent.getAction())) || !Store.netBlocked(this)) {
            close();
            stopSelf();
            return START_NOT_STICKY;
        }
        establish();
        return START_STICKY;
    }

    private synchronized void establish() {
        close();
        try {
            Builder b = new Builder();
            b.setSession("Familienzeit: Internet-Pause");
            b.addAddress("10.111.0.1", 32);
            b.addAddress("fd11:1::1", 128);
            b.addRoute("0.0.0.0", 0);
            b.addRoute("::", 0);
            b.addDnsServer("10.111.0.2");
            b.setBlocking(true);
            Set<String> allowed = Store.strings(this, "netAllowed");
            allowed.add(getPackageName());
            for (String p : allowed) {
                try {
                    b.addDisallowedApplication(p);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            tun = b.establish();
            if (tun == null) return;
            final ParcelFileDescriptor fd = tun;
            drain = new Thread(() -> {
                byte[] buf = new byte[32767];
                try (FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
                    while (!Thread.currentThread().isInterrupted()) {
                        if (in.read(buf) < 0) break;
                    }
                } catch (Exception ignored) {
                }
            }, "vpn-drain");
            drain.start();
        } catch (Exception ignored) {
        }
    }

    private synchronized void close() {
        if (drain != null) {
            drain.interrupt();
            drain = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (Exception ignored) {
            }
            tun = null;
        }
    }

    @Override
    public void onRevoke() {
        close();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        close();
        super.onDestroy();
    }
}
