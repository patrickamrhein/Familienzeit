package de.familienzeit.kind;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.Process;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Berechnet die heutige Nutzung aus den Android-Nutzungsdaten. */
final class Usage {
    long screenMs;
    long netMs;
    int unlocks;
    final Map<String, Long> perApp = new HashMap<>();

    private static final Map<String, Boolean> internetCache = new HashMap<>();

    static boolean hasAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return c.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    static long startOfDay() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /**
     * @param notCounted Apps, die nicht zur Bildschirmzeit zählen (Startbildschirm, "immer erlaubt" …)
     */
    static Usage today(Context c, Set<String> notCounted) {
        Usage u = new Usage();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(startOfDay(), now);
        if (ev == null) return u;

        Map<String, Set<String>> open = new HashMap<>();
        Map<String, Long> since = new HashMap<>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            String pkg = e.getPackageName();
            String cls = e.getClassName() == null ? "" : e.getClassName();
            long t = e.getTimeStamp();
            switch (e.getEventType()) {
                case UsageEvents.Event.ACTIVITY_RESUMED: {
                    Set<String> s = open.get(pkg);
                    if (s == null) {
                        s = new HashSet<>();
                        open.put(pkg, s);
                    }
                    if (s.isEmpty()) since.put(pkg, t);
                    s.add(cls);
                    break;
                }
                case UsageEvents.Event.ACTIVITY_PAUSED:
                case UsageEvents.Event.ACTIVITY_STOPPED: {
                    Set<String> s = open.get(pkg);
                    if (s != null && s.remove(cls) && s.isEmpty()) u.add(pkg, t - since.get(pkg));
                    break;
                }
                case UsageEvents.Event.SCREEN_NON_INTERACTIVE:
                    closeAll(u, open, since, t);
                    break;
                case UsageEvents.Event.KEYGUARD_HIDDEN:
                    u.unlocks++;
                    break;
                default:
                    break;
            }
        }
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm.isInteractive()) closeAll(u, open, since, now);

        PackageManager pkm = c.getPackageManager();
        for (Map.Entry<String, Long> en : u.perApp.entrySet()) {
            if (notCounted.contains(en.getKey())) continue;
            u.screenMs += en.getValue();
            if (usesInternet(pkm, en.getKey())) u.netMs += en.getValue();
        }
        return u;
    }

    private static void closeAll(Usage u, Map<String, Set<String>> open, Map<String, Long> since, long t) {
        for (Map.Entry<String, Set<String>> en : open.entrySet()) {
            if (!en.getValue().isEmpty()) {
                u.add(en.getKey(), t - since.get(en.getKey()));
                en.getValue().clear();
            }
        }
    }

    private void add(String pkg, long ms) {
        if (ms <= 0) return;
        Long old = perApp.get(pkg);
        perApp.put(pkg, (old == null ? 0 : old) + ms);
    }

    private static boolean usesInternet(PackageManager pm, String pkg) {
        Boolean b = internetCache.get(pkg);
        if (b == null) {
            b = pm.checkPermission(Manifest.permission.INTERNET, pkg) == PackageManager.PERMISSION_GRANTED;
            internetCache.put(pkg, b);
        }
        return b;
    }

    long appMin(String pkg) {
        Long v = perApp.get(pkg);
        return v == null ? 0 : v / 60000L;
    }
}
