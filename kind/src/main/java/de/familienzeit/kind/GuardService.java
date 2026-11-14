package de.familienzeit.kind;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import de.familienzeit.common.Crypto;
import de.familienzeit.common.Ntfy;
import de.familienzeit.common.Updater;

/** Läuft dauerhaft: misst die Nutzung, setzt die Regeln durch und spricht mit der Eltern-App. */
public class GuardService extends Service {
    private static final String CH_STATUS = "status";
    private static final String CH_WARN = "warn";
    private static final int NOTIF_ID = 1;

    private static final long POLL_MS = 60_000;
    private static final long STATUS_MS = 20 * 60_000;
    private static final long APPS_MS = 6 * 3600_000;
    private static final long UPDATE_MS = 6 * 3600_000;

    private HandlerThread thread;
    private Handler h;
    private final Runnable tick = this::tick;
    private boolean forceStatus = true;
    private boolean forceUpdate = false;
    private String lastLock = null;

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            kick();
        }
    };
    private final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i.getData() == null) return;
            final String pkg = i.getData().getSchemeSpecificPart();
            h.post(() -> {
                Policy.resuspend(GuardService.this, pkg);
                Store.p(GuardService.this).edit().putLong("appsSent", 0).apply();
            });
            kick();
        }
    };

    static void start(Context c) {
        Intent i = new Intent(c, GuardService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Bildschirmzeit", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_WARN, "Hinweise", NotificationManager.IMPORTANCE_HIGH));
        Notification n = statusNotif("Familienzeit ist aktiv");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }

        thread = new HandlerThread("guard");
        thread.start();
        h = new Handler(thread.getLooper());

        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenRx, sf);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgRx, pf);

        h.post(() -> Policy.applyBase(this, Store.cfg(this).optBoolean("debug", false)));
        kick();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        kick();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(screenRx);
            unregisterReceiver(pkgRx);
        } catch (Exception ignored) {
        }
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void kick() {
        if (h == null) return;
        h.removeCallbacks(tick);
        h.post(tick);
    }

    private boolean interactive() {
        return ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
    }

    private void tick() {
        try {
            work();
        } catch (Throwable t) {
            Store.p(this).edit().putString("lastError", String.valueOf(t)).apply();
        }
        h.removeCallbacks(tick);
        h.postDelayed(tick, interactive() ? 20_000 : 5 * 60_000);
    }

    // ---------------------------------------------------------------- Hauptschleife

    private void work() {
        if (!Policy.isOwner(this)) {
            updateNotif("Nicht eingerichtet");
            return;
        }
        String code = Store.code(this);
        if (code.isEmpty()) {
            updateNotif("Noch nicht mit der Eltern-App gekoppelt");
            return;
        }
        SharedPreferences sp = Store.p(this);
        long now = System.currentTimeMillis();

        if (now - sp.getLong("lastPoll", 0) >= (interactive() ? POLL_MS : 0)) {
            sp.edit().putLong("lastPoll", now).apply();
            try {
                pollRemote(code);
            } catch (Exception ignored) {
            }
            if (!Policy.isOwner(this)) return; // wurde freigegeben
        }

        JSONObject cfg = Store.cfg(this);
        Set<String> allowed = Store.set(cfg, "allowed");
        Set<String> blocked = Store.set(cfg, "blocked");
        JSONObject limits = cfg.optJSONObject("limits");
        if (limits == null) limits = new JSONObject();

        Set<String> essentials = Policy.essentials(this);
        Set<String> notCounted = new HashSet<>(essentials);
        notCounted.addAll(allowed);

        boolean access = Usage.hasAccess(this);
        Usage u = access ? Usage.today(this, notCounted) : new Usage();
        long screenMin = u.screenMs / 60000;
        long netMin = u.netMs / 60000;

        int bonus = Store.today().equals(cfg.optString("bonusDay")) ? cfg.optInt("bonus", 0) : 0;
        int daily = cfg.optInt("daily", 0);
        int net = cfg.optInt("net", 0);
        long dailyMax = daily > 0 ? daily + bonus : 0;
        long netMax = net > 0 ? net + bonus : 0;

        // --- Grund für eine komplette Sperre
        String lock = null;
        if (!access) lock = "Schutz verletzt (Nutzungszugriff entzogen)";
        else if (cfg.optLong("pauseUntil", 0) > now) lock = "Pause durch Eltern";
        else if (nightActive(cfg)) lock = "Nachtruhe";
        else if (dailyMax > 0 && screenMin >= dailyMax) lock = "Tageslimit erreicht";

        // --- Neue Apps: gesperrt, bis die Eltern sie freigeben
        List<ResolveInfo> apps = Policy.launchable(this);
        PackageManager pm = getPackageManager();
        Set<String> known = Store.strings(this, "known");
        Set<String> mentioned = new HashSet<>(allowed);
        mentioned.addAll(blocked);
        mentioned.addAll(Store.set(cfg, "approved"));
        java.util.Iterator<String> lk = limits.keys();
        while (lk.hasNext()) mentioned.add(lk.next());
        boolean firstRun = !sp.getBoolean("knownInit", false);
        Set<String> pending = new HashSet<>();
        for (ResolveInfo ri : apps) {
            String p = ri.activityInfo.packageName;
            if (known.contains(p) || essentials.contains(p)) continue;
            if (firstRun || mentioned.contains(p)) {
                known.add(p);
            } else {
                pending.add(p);
                if (Store.firstTimeToday(this, "new:" + p)) {
                    event("📲 Neue App installiert: " + label(pm, p) + " – gesperrt, bis du sie freigibst");
                    sp.edit().putLong("appsSent", 0).apply();
                }
            }
        }
        Store.putStrings(this, "known", known);
        Store.putStrings(this, "pending", pending);
        if (firstRun) sp.edit().putBoolean("knownInit", true).apply();

        // --- Welche Apps werden gesperrt?
        Set<String> target = new HashSet<>(pending);
        List<String> appEvents = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String p = ri.activityInfo.packageName;
            if (essentials.contains(p) || allowed.contains(p)) continue;
            if (lock != null || blocked.contains(p)) {
                target.add(p);
                continue;
            }
            int lim = limits.optInt(p, 0);
            if (lim > 0 && u.appMin(p) >= lim + bonus) {
                target.add(p);
                appEvents.add(p);
            }
        }
        Store.putStrings(this, "wantedSuspended", target);
        Policy.applySuspension(this, target);

        boolean netBlocked = lock != null || (netMax > 0 && netMin >= netMax);
        Policy.setInternetBlocked(this, netBlocked, allowed);

        // --- Hinweise für das Kind und Ereignisse für die Eltern
        if (dailyMax > 0 && lock == null && dailyMax - screenMin <= 10 && Store.firstTimeToday(this, "warnDaily")) {
            warn("Noch " + (dailyMax - screenMin) + " Minuten Bildschirmzeit für heute.");
        }
        if (netMax > 0 && !netBlocked && netMax - netMin <= 10 && Store.firstTimeToday(this, "warnNet")) {
            warn("Noch " + (netMax - netMin) + " Minuten Internet für heute.");
        }
        if (lock != null && !lock.equals(lastLock) && Store.firstTimeToday(this, "lock:" + lock)) {
            event((lock.startsWith("Schutz") ? "⚠️ " : "🔒 ") + lock + " – Bildschirmzeit " + hm(screenMin));
            forceStatus = true;
        }
        lastLock = lock;
        if (netBlocked && lock == null && Store.firstTimeToday(this, "netLimit")) {
            event("🌐 Internetzeit aufgebraucht (" + hm(netMin) + ")");
        }
        for (String p : appEvents) {
            if (Store.firstTimeToday(this, "app:" + p)) event("⏱️ " + label(pm, p) + ": Limit erreicht");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Heute ").append(hm(screenMin));
        if (dailyMax > 0) sb.append(" von ").append(hm(dailyMax));
        if (netMax > 0) sb.append(" · Internet ").append(hm(netMin)).append(" von ").append(hm(netMax));
        if (lock != null) sb.append(" · ").append(lock);
        String text = sb.toString();
        updateNotif(text);
        sp.edit().putString("statusText", text).putLong("screenMin", screenMin).apply();

        // --- Status an die Eltern
        if (forceStatus || now - sp.getLong("statusSent", 0) >= STATUS_MS) {
            try {
                sendStatus(code, u, screenMin, netMin, lock, netBlocked, apps, pm);
                sp.edit().putLong("statusSent", now).apply();
                forceStatus = false;
            } catch (Exception ignored) {
            }
        }
        if (now - sp.getLong("appsSent", 0) >= APPS_MS) {
            try {
                sendApps(code, apps, pm);
                sp.edit().putLong("appsSent", now).apply();
            } catch (Exception ignored) {
            }
        }

        // --- Updates
        if (forceUpdate || now - sp.getLong("updateCheck", 0) >= UPDATE_MS) {
            forceUpdate = false;
            sp.edit().putLong("updateCheck", now).apply();
            checkUpdate();
        }
    }

    private boolean nightActive(JSONObject cfg) {
        if (!cfg.optBoolean("nightOn", false)) return false;
        int from = minutes(cfg.optString("nightFrom", "21:00"));
        int to = minutes(cfg.optString("nightTo", "07:00"));
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (from == to) return false;
        if (from < to) return now >= from && now < to;
        return now >= from || now < to;
    }

    private static int minutes(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static String hm(long min) {
        return (min / 60) + ":" + String.format("%02d", min % 60) + " h";
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---------------------------------------------------------------- Kommunikation

    private void pollRemote(String code) throws Exception {
        SharedPreferences sp = Store.p(this);
        List<Ntfy.Msg> msgs = Ntfy.poll(Crypto.topic(code, "cfg"), sp.getString("lastId", "all"));
        for (Ntfy.Msg m : msgs) {
            sp.edit().putString("lastId", m.id).apply();
            String plain = Crypto.decrypt(code, m.body);
            if (plain == null) continue;
            JSONObject o;
            try {
                o = new JSONObject(plain);
            } catch (Exception e) {
                continue;
            }
            String type = o.optString("type");
            if ("cfg".equals(type)) {
                JSONObject c = o.optJSONObject("cfg");
                if (c != null && c.optLong("v", 0) > Store.cfg(this).optLong("v", 0)) {
                    boolean oldDebug = Store.cfg(this).optBoolean("debug", false);
                    Store.saveCfg(this, c);
                    if (c.optBoolean("debug", false) != oldDebug) Policy.applyBase(this, c.optBoolean("debug", false));
                    forceStatus = true;
                }
            } else if ("cmd".equals(type)) {
                long ts = o.optLong("ts", 0);
                long now = System.currentTimeMillis();
                if (ts <= sp.getLong("lastCmdTs", 0) || now - ts > 24 * 3600_000L) continue;
                sp.edit().putLong("lastCmdTs", ts).apply();
                String cmd = o.optString("cmd");
                if ("refresh".equals(cmd)) {
                    forceStatus = true;
                    sp.edit().putLong("appsSent", 0).apply();
                } else if ("update".equals(cmd)) {
                    forceUpdate = true;
                } else if ("locate".equals(cmd)) {
                    locate();
                } else if ("release".equals(cmd)) {
                    try {
                        event("🔓 Kontrolle wurde aufgehoben. Die App kann jetzt deinstalliert werden.");
                    } catch (Exception ignored) {
                    }
                    Policy.release(this);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                    return;
                }
            }
        }
    }

    private void publish(JSONObject o) throws Exception {
        String code = Store.code(this);
        Ntfy.publish(Crypto.topic(code, "st"), Crypto.encrypt(code, o.toString()));
    }

    private void event(String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "ev");
            o.put("ts", System.currentTimeMillis());
            o.put("text", text);
            publish(o);
        } catch (Exception ignored) {
        }
    }

    private void sendStatus(String code, Usage u, long screenMin, long netMin, String lock, boolean netBlocked,
                            List<ResolveInfo> apps, PackageManager pm) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "st");
        o.put("ts", System.currentTimeMillis());
        o.put("ver", BuildConfig.VERSION_CODE);
        o.put("cfgV", Store.cfg(this).optLong("v", 0));
        o.put("screen", screenMin);
        o.put("net", netMin);
        o.put("unlocks", u.unlocks);
        o.put("lock", lock == null ? "" : lock);
        o.put("netBlocked", netBlocked);
        o.put("access", Usage.hasAccess(this));

        // die meistgenutzten Apps
        TreeMap<Long, List<String>> byTime = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<String, Long> en : u.perApp.entrySet()) {
            if (en.getValue() < 60000) continue;
            List<String> l = byTime.get(en.getValue());
            if (l == null) {
                l = new ArrayList<>();
                byTime.put(en.getValue(), l);
            }
            l.add(en.getKey());
        }
        JSONArray top = new JSONArray();
        outer:
        for (Map.Entry<Long, List<String>> en : byTime.entrySet()) {
            for (String p : en.getValue()) {
                if (p.equals(getPackageName())) continue;
                JSONArray row = new JSONArray();
                row.put(p);
                row.put(label(pm, p));
                row.put(en.getKey() / 60000);
                top.put(row);
                if (top.length() >= 15) break outer;
            }
        }
        o.put("top", top);
        publish(o);
    }

    private void sendApps(String code, List<ResolveInfo> apps, PackageManager pm) throws Exception {
        Set<String> essentials = Policy.essentials(this);
        JSONArray arr = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : apps) {
            String p = ri.activityInfo.packageName;
            if (essentials.contains(p) || !seen.add(p)) continue;
            JSONArray row = new JSONArray();
            row.put(p);
            row.put(label(pm, p));
            arr.put(row);
        }
        JSONObject o = new JSONObject();
        o.put("type", "apps");
        o.put("ts", System.currentTimeMillis());
        o.put("apps", arr);
        o.put("pending", new JSONArray(Store.strings(this, "pending")));
        publish(o);
    }

    private void checkUpdate() {
        String repo = BuildConfig.UPDATE_REPO;
        int remote = Updater.remoteVersion(repo);
        if (remote <= BuildConfig.VERSION_CODE) return;
        Policy.allowSelfUpdate(this);
        String err = Updater.downloadAndInstall(this, repo, "kind.apk", remote, InstallReceiver.class);
        if (err != null) {
            Policy.applyBase(this, Store.cfg(this).optBoolean("debug", false));
            event("⚠️ " + err);
        } else {
            event("⬆️ Update auf Version " + remote + " wird installiert");
        }
    }

    // ---------------------------------------------------------------- Standort

    private void locate() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            event("⚠️ Standort: keine Berechtigung");
            return;
        }
        final android.location.LocationManager lm =
                (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        final boolean[] done = {false};
        final Runnable fallback = () -> {
            if (done[0]) return;
            done[0] = true;
            sendLocation(bestLastKnown(lm), true);
        };
        if (Build.VERSION.SDK_INT >= 30) {
            String provider = Build.VERSION.SDK_INT >= 31 && lm.hasProvider(android.location.LocationManager.FUSED_PROVIDER)
                    ? android.location.LocationManager.FUSED_PROVIDER
                    : android.location.LocationManager.GPS_PROVIDER;
            try {
                lm.getCurrentLocation(provider, null, h::post, loc -> {
                    if (done[0]) return;
                    done[0] = true;
                    sendLocation(loc != null ? loc : bestLastKnown(lm), loc == null);
                });
            } catch (Exception e) {
                fallback.run();
                return;
            }
            h.postDelayed(fallback, 45_000);
        } else {
            fallback.run();
        }
    }

    @SuppressWarnings("MissingPermission")
    private android.location.Location bestLastKnown(android.location.LocationManager lm) {
        android.location.Location best = null;
        for (String p : lm.getAllProviders()) {
            try {
                android.location.Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private void sendLocation(android.location.Location loc, boolean old) {
        if (loc == null) {
            event("⚠️ Standort konnte nicht ermittelt werden");
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("type", "loc");
            o.put("ts", System.currentTimeMillis());
            o.put("fix", loc.getTime());
            o.put("lat", loc.getLatitude());
            o.put("lon", loc.getLongitude());
            o.put("acc", Math.round(loc.getAccuracy()));
            o.put("old", old);
            publish(o);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- Benachrichtigungen

    private Notification statusNotif(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Familienzeit")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotif(String text) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, statusNotif(text));
    }

    private void warn(String text) {
        Notification n = new Notification.Builder(this, CH_WARN)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Familienzeit")
                .setContentText(text)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(2, n);
    }
}
