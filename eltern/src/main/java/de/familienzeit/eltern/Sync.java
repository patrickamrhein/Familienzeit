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

import java.util.ArrayList;
import java.util.List;

import de.familienzeit.common.Crypto;
import de.familienzeit.common.Keys;
import de.familienzeit.common.Ntfy;

/**
 * Datenaustausch mit den Kinder-Handys über ntfy.sh.
 * Alles ist mit dem Familienschlüssel des jeweiligen Kindes verschlüsselt,
 * Befehle werden zusätzlich mit dem persönlichen Schlüssel dieses Eltern-Handys unterschrieben.
 */
final class Sync {
    private static final String CH = "events";

    private Sync() {}

    // ------------------------------------------------------------ eigenes Eltern-Handy

    static SharedPreferences me(Context c) {
        return c.getSharedPreferences("fz_me", Context.MODE_PRIVATE);
    }

    static void ensureIdentity(Context c) {
        SharedPreferences sp = me(c);
        if (!sp.getString("priv", "").isEmpty()) return;
        try {
            String[] kp = Keys.generate();
            sp.edit().putString("priv", kp[0]).putString("pub", kp[1]).putString("id", Keys.id(kp[1])).apply();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String myId(Context c) {
        return me(c).getString("id", "");
    }

    static String myName(Context c) {
        return me(c).getString("name", "");
    }

    // ------------------------------------------------------------ Kinder

    static List<String> children(Context c) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(me(c).getString("children", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    static String selected(Context c) {
        List<String> all = children(c);
        String s = me(c).getString("selected", "");
        if (all.contains(s)) return s;
        return all.isEmpty() ? "" : all.get(0);
    }

    static void select(Context c, String lid) {
        me(c).edit().putString("selected", lid).apply();
    }

    static SharedPreferences child(Context c, String lid) {
        return c.getSharedPreferences("fz_child_" + lid, Context.MODE_PRIVATE);
    }

    static String addChild(Context c, String key) {
        String lid = Crypto.sha(key + System.nanoTime()).substring(0, 10);
        child(c, lid).edit().putString("key", key).apply();
        List<String> all = children(c);
        all.add(lid);
        me(c).edit().putString("children", new JSONArray(all).toString()).putString("selected", lid).apply();
        return lid;
    }

    static void removeChild(Context c, String lid) {
        child(c, lid).edit().clear().apply();
        List<String> all = children(c);
        all.remove(lid);
        me(c).edit().putString("children", new JSONArray(all).toString()).apply();
    }

    static String key(Context c, String lid) {
        return child(c, lid).getString("key", "");
    }

    static JSONObject json(Context c, String lid, String k) {
        try {
            return new JSONObject(child(c, lid).getString(k, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static JSONArray events(Context c, String lid) {
        try {
            return new JSONArray(child(c, lid).getString("events", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static String childName(Context c, String lid) {
        String n = json(c, lid, "cfg").optString("name", "");
        return n.isEmpty() ? "Kind" : n;
    }

    /** "admin", "parent" oder "" (noch nicht bestätigt). */
    static String myRole(Context c, String lid) {
        JSONArray ps = json(c, lid, "status").optJSONArray("parents");
        String me = myId(c);
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject o = ps.optJSONObject(i);
                if (o != null && me.equals(o.optString("id"))) return o.optString("role");
            }
        }
        return "";
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

    private static void sendSigned(Context c, String lid, JSONObject d) throws Exception {
        d.put("ts", System.currentTimeMillis());
        String data = d.toString();
        JSONObject env = new JSONObject();
        env.put("p", myId(c));
        env.put("d", data);
        env.put("s", Keys.sign(me(c).getString("priv", ""), data));
        String key = key(c, lid);
        Ntfy.publish(Crypto.topic(key, "cfg"), Crypto.encrypt(key, env.toString()));
    }

    static void join(Context c, String lid, String secret) throws Exception {
        JSONObject d = new JSONObject();
        d.put("type", "join");
        d.put("pub", me(c).getString("pub", ""));
        d.put("name", myName(c));
        d.put("s", secret);
        sendSigned(c, lid, d);
    }

    static void sendCfg(Context c, String lid, JSONObject cfg) throws Exception {
        cfg.put("v", System.currentTimeMillis());
        child(c, lid).edit().putString("cfg", cfg.toString()).putLong("cfgSent", System.currentTimeMillis()).apply();
        JSONObject d = new JSONObject();
        d.put("type", "cfg");
        d.put("cfg", cfg);
        sendSigned(c, lid, d);
    }

    static void sendCmd(Context c, String lid, String cmd, JSONObject extra) throws Exception {
        JSONObject d = extra == null ? new JSONObject() : extra;
        d.put("type", "cmd");
        d.put("cmd", cmd);
        sendSigned(c, lid, d);
    }

    // ------------------------------------------------------------ Empfangen

    static boolean pollAll(Context c, boolean notify) {
        boolean changed = false;
        for (String lid : children(c)) {
            try {
                changed |= poll(c, lid, notify);
            } catch (Exception ignored) {
            }
        }
        return changed;
    }

    /** Holt neue Nachrichten eines Kindes. Gibt true zurück, wenn sich etwas geändert hat. */
    static synchronized boolean poll(Context c, String lid, boolean notify) throws Exception {
        for (int round = 0; round < 2; round++) {
            String newKey = pollOnce(c, lid, notify);
            if (newKey == null) return true;
            child(c, lid).edit().putString("key", newKey).putString("lastId", "all").apply();
        }
        return true;
    }

    /** @return neuer Familienschlüssel, falls das Kinder-Handy einen geschickt hat, sonst null */
    private static String pollOnce(Context c, String lid, boolean notify) throws Exception {
        SharedPreferences sp = child(c, lid);
        String key = key(c, lid);
        if (key.isEmpty()) return null;
        List<Ntfy.Msg> msgs = Ntfy.poll(Crypto.topic(key, "st"), sp.getString("lastId", "all"));
        long now = System.currentTimeMillis();
        long lastNotified = sp.getLong("lastNotified", 0);
        JSONArray evs = events(c, lid);
        String me = myId(c);
        String rekey = null;
        for (Ntfy.Msg m : msgs) {
            sp.edit().putString("lastId", m.id).apply();
            String plain = Crypto.decrypt(key, m.body);
            if (plain == null) continue;
            JSONObject o;
            try {
                o = new JSONObject(plain);
            } catch (Exception e) {
                continue;
            }
            String type = o.optString("type");
            long ts = o.optLong("ts", 0);
            if ("rekey".equals(type)) {
                if (!me.equals(o.optString("to"))) continue;
                String k = Keys.open(me(c).getString("priv", ""), o.optString("box"));
                if (k != null && !k.equals(key)) rekey = k;
            } else if ("st".equals(type)) {
                if (ts >= json(c, lid, "status").optLong("ts", 0)) {
                    sp.edit().putString("status", o.toString()).apply();
                    JSONObject remoteCfg = o.optJSONObject("cfg");
                    if (remoteCfg != null && remoteCfg.optLong("v", 0) > json(c, lid, "cfg").optLong("v", 0)) {
                        sp.edit().putString("cfg", remoteCfg.toString()).apply();
                    }
                }
            } else if ("apps".equals(type)) {
                sp.edit().putString("apps", o.toString()).apply();
            } else if ("loc".equals(type)) {
                sp.edit().putString("loc", o.toString()).apply();
            } else if ("ev".equals(type)) {
                evs.put(o);
                if (notify && ts > lastNotified && now - ts < 3 * 3600_000L) {
                    notifyEvent(c, lid, o.optString("text"));
                    lastNotified = ts;
                }
            }
        }
        while (evs.length() > 40) evs.remove(0);
        sp.edit().putString("events", evs.toString())
                .putLong("lastNotified", Math.max(lastNotified, now - 60_000)).apply();
        if (rekey != null) return rekey;

        // Regeln erneut schicken, falls das Kinder-Handy sie verpasst hat
        JSONObject cfg = json(c, lid, "cfg");
        JSONObject st = json(c, lid, "status");
        if (cfg.has("v") && st.has("cfgV") && st.optLong("cfgV") < cfg.optLong("v")
                && st.optLong("ts") > cfg.optLong("v") + 120_000
                && now - sp.getLong("cfgSent", 0) > 10 * 60_000) {
            try {
                sendCfg(c, lid, cfg);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static void notifyEvent(Context c, String lid, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Meldungen vom Kinder-Handy",
                NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(c, MainActivity.class).putExtra("lid", lid);
        PendingIntent pi = PendingIntent.getActivity(c, lid.hashCode(), open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_menu_myplaces)
                .setContentTitle("Familienzeit · " + childName(c, lid))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify((int) (System.currentTimeMillis() % 100000), n);
    }
}
