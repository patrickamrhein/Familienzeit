package de.familienzeit.eltern;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import de.familienzeit.common.Crypto;
import de.familienzeit.common.Ntfy;

/** Datenaustausch mit dem Kinder-Handy über ntfy.sh (Ende-zu-Ende verschlüsselt). */
final class Sync {
    private static final String CH = "events";

    private Sync() {}

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("fz", Context.MODE_PRIVATE);
    }

    static String code(Context c) {
        return p(c).getString("code", "");
    }

    /** Neues Kinder-Handy koppeln: alte Daten verwerfen, Code speichern, Hallo senden. */
    static void pair(Context c, String code) throws Exception {
        p(c).edit().clear().putString("code", Crypto.normalize(code)).apply();
        sendCmd(c, "hello");
    }

    static JSONObject json(Context c, String key) {
        try {
            return new JSONObject(p(c).getString(key, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONArray events(Context c) {
        try {
            return new JSONArray(p(c).getString("events", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(1, new ComponentName(c, PollJob.class))
                .setPeriodic(15 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    // ------------------------------------------------------------ Senden

    static void sendCfg(Context c, JSONObject cfg) throws Exception {
        cfg.put("v", System.currentTimeMillis());
        p(c).edit().putString("cfg", cfg.toString()).putLong("cfgSent", System.currentTimeMillis()).apply();
        JSONObject o = new JSONObject();
        o.put("type", "cfg");
        o.put("cfg", cfg);
        String code = code(c);
        Ntfy.publish(Crypto.topic(code, "cfg"), Crypto.encrypt(code, o.toString()));
    }

    static void sendCmd(Context c, String cmd) throws Exception {
        JSONObject o = new JSONObject();
        o.put("type", "cmd");
        o.put("cmd", cmd);
        o.put("ts", System.currentTimeMillis());
        String code = code(c);
        Ntfy.publish(Crypto.topic(code, "cfg"), Crypto.encrypt(code, o.toString()));
    }

    // ------------------------------------------------------------ Empfangen

    /** Holt neue Nachrichten. Gibt true zurück, wenn sich etwas geändert hat. */
    static synchronized boolean poll(Context c, boolean notify) throws Exception {
        SharedPreferences sp = p(c);
        String code = code(c);
        if (code.isEmpty()) return false;
        List<Ntfy.Msg> msgs = Ntfy.poll(Crypto.topic(code, "st"), sp.getString("lastId", "all"));
        boolean changed = false;
        long now = System.currentTimeMillis();
        long lastNotified = sp.getLong("lastNotified", 0);
        JSONArray evs = events(c);
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
            changed = true;
            String type = o.optString("type");
            long ts = o.optLong("ts", 0);
            if ("st".equals(type)) {
                if (ts >= json(c, "status").optLong("ts", 0)) sp.edit().putString("status", o.toString()).apply();
            } else if ("loc".equals(type)) {
                sp.edit().putString("loc", o.toString()).apply();
            } else if ("apps".equals(type)) {
                sp.edit().putString("apps", o.toString()).apply();
            } else if ("ev".equals(type)) {
                evs.put(o);
                if (notify && ts > lastNotified && now - ts < 3 * 3600_000L) {
                    notifyEvent(c, o.optString("text"));
                    lastNotified = ts;
                }
            }
        }
        while (evs.length() > 40) evs.remove(0);
        sp.edit().putString("events", evs.toString()).putLong("lastNotified", Math.max(lastNotified, now - 60_000)).apply();

        // Regeln erneut schicken, falls das Kinder-Handy sie verpasst hat
        JSONObject cfg = json(c, "cfg");
        JSONObject st = json(c, "status");
        if (cfg.has("v") && st.has("cfgV") && st.optLong("cfgV") < cfg.optLong("v")
                && st.optLong("ts") > cfg.optLong("v") + 120_000
                && now - sp.getLong("cfgSent", 0) > 10 * 60_000) {
            try {
                sendCfg(c, cfg);
            } catch (Exception ignored) {
            }
        }
        return changed;
    }

    static void notifyEvent(Context c, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Meldungen vom Kinder-Handy", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle("Familienzeit")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (System.currentTimeMillis() % 100000), n);
    }
}
