package de.familienzeit.kind;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import de.familienzeit.common.Crypto;
import de.familienzeit.common.Keys;
import de.familienzeit.common.Ntfy;

/**
 * Wer darf dieses Handy steuern? Liste der Eltern-Handys mit Rolle (admin / parent),
 * offene Einladungen und Wechsel des Familienschlüssels, wenn jemand entfernt wird.
 */
final class Family {
    static final String ADMIN = "admin";
    static final String PARENT = "parent";
    private static final long KEEP_OLD_KEY_MS = 7L * 24 * 3600_000L;

    private Family() {}

    private static SharedPreferences p(Context c) {
        return Store.p(c);
    }

    static String key(Context c) {
        return p(c).getString("key", "");
    }

    /** Beim ersten Start: Schlüssel und Admin-Einladung für den QR-Code anlegen. */
    static void init(Context c) {
        if (!key(c).isEmpty()) return;
        p(c).edit()
                .putString("key", Crypto.newKey())
                .putString("parents", "[]")
                .putString("invites", "[]")
                .apply();
        newQrInvite(c);
    }

    /** Erzeugt eine Admin-Einladung, die direkt am Kinder-Handy als QR-Code gezeigt wird. */
    static void newQrInvite(Context c) {
        String s = Crypto.newSecret();
        addInvite(c, Crypto.sha(s), ADMIN, 0);
        p(c).edit().putString("qrSecret", s).apply();
    }

    static String qrSecret(Context c) {
        return p(c).getString("qrSecret", "");
    }

    static JSONArray parents(Context c) {
        try {
            return new JSONArray(p(c).getString("parents", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static boolean paired(Context c) {
        return parents(c).length() > 0;
    }

    static JSONObject parent(Context c, String id) {
        JSONArray a = parents(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    private static int adminCount(JSONArray a) {
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && ADMIN.equals(o.optString("role"))) n++;
        }
        return n;
    }

    private static void saveParents(Context c, JSONArray a) {
        p(c).edit().putString("parents", a.toString()).apply();
    }

    static void addInvite(Context c, String hash, String role, long exp) {
        try {
            JSONArray a = new JSONArray(p(c).getString("invites", "[]"));
            JSONObject o = new JSONObject();
            o.put("h", hash);
            o.put("role", role);
            o.put("exp", exp);
            a.put(o);
            p(c).edit().putString("invites", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * Ein neues Eltern-Handy möchte beitreten. Gelingt nur mit gültiger Einladung.
     * @return Name und Rolle für die Meldung, oder null
     */
    static String join(Context c, String id, JSONObject d) {
        try {
            String pub = d.getString("pub");
            if (!Keys.id(pub).equals(id)) return null;
            String hash = Crypto.sha(d.getString("s"));
            JSONArray inv = new JSONArray(p(c).getString("invites", "[]"));
            JSONArray rest = new JSONArray();
            String role = null;
            long now = System.currentTimeMillis();
            for (int i = 0; i < inv.length(); i++) {
                JSONObject o = inv.getJSONObject(i);
                boolean valid = o.optLong("exp") == 0 || o.optLong("exp") > now;
                if (role == null && valid && hash.equals(o.optString("h"))) role = o.optString("role", PARENT);
                else if (valid) rest.put(o);
            }
            if (role == null) return null;
            p(c).edit().putString("invites", rest.toString()).apply();
            if (Crypto.sha(qrSecret(c)).equals(hash)) p(c).edit().remove("qrSecret").apply();

            JSONArray ps = parents(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < ps.length(); i++) {
                JSONObject o = ps.getJSONObject(i);
                if (!id.equals(o.optString("id"))) out.put(o);
            }
            JSONObject np = new JSONObject();
            np.put("id", id);
            np.put("pub", pub);
            np.put("name", d.optString("name", "Elternteil"));
            np.put("role", role);
            np.put("since", now);
            out.put(np);
            saveParents(c, out);
            return np.optString("name") + (ADMIN.equals(role) ? " (Admin)" : " (Elternteil)");
        } catch (Exception e) {
            return null;
        }
    }

    /** Entfernt ein Eltern-Handy. Der letzte Admin kann nicht entfernt werden. */
    static String remove(Context c, String id) {
        JSONArray ps = parents(c);
        JSONArray out = new JSONArray();
        String name = null;
        for (int i = 0; i < ps.length(); i++) {
            JSONObject o = ps.optJSONObject(i);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) name = o.optString("name");
            else out.put(o);
        }
        if (name == null || adminCount(out) == 0) return null;
        saveParents(c, out);
        rekey(c);
        return name;
    }

    static String setRole(Context c, String id, String role) {
        if (!ADMIN.equals(role) && !PARENT.equals(role)) return null;
        JSONArray ps = parents(c);
        String name = null;
        try {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject o = ps.getJSONObject(i);
                if (id.equals(o.optString("id"))) {
                    o.put("role", role);
                    name = o.optString("name");
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (name == null || adminCount(ps) == 0) return null;
        saveParents(c, ps);
        return name;
    }

    /** Öffentliche Liste (ohne Schlüssel) für die Eltern-Apps. */
    static JSONArray publicList(Context c) {
        JSONArray ps = parents(c), out = new JSONArray();
        for (int i = 0; i < ps.length(); i++) {
            JSONObject o = ps.optJSONObject(i);
            if (o == null) continue;
            try {
                JSONObject x = new JSONObject();
                x.put("id", o.optString("id"));
                x.put("name", o.optString("name"));
                x.put("role", o.optString("role"));
                out.put(x);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * Neuer Familienschlüssel, z. B. nachdem ein Elternteil entfernt wurde.
     * Die verbleibenden Eltern-Handys bekommen ihn einzeln verschlüsselt über den alten Kanal.
     */
    static void rekey(Context c) {
        try {
            String old = key(c);
            JSONArray olds = new JSONArray(p(c).getString("oldKeys", "[]"));
            JSONArray keep = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < olds.length(); i++) {
                JSONObject o = olds.getJSONObject(i);
                if (o.optLong("until") > now) keep.put(o);
            }
            JSONObject o = new JSONObject();
            o.put("key", old);
            o.put("until", now + KEEP_OLD_KEY_MS);
            keep.put(o);
            p(c).edit()
                    .putString("key", Crypto.newKey())
                    .putString("oldKeys", keep.toString())
                    .putString("lastId", "all")
                    .putLong("rekeySent", 0)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** Verteilt den aktuellen Schlüssel über alle noch gültigen alten Kanäle (wiederholt, falls jemand offline war). */
    static void sendRekeys(Context c) {
        try {
            JSONArray olds = new JSONArray(p(c).getString("oldKeys", "[]"));
            if (olds.length() == 0) return;
            long now = System.currentTimeMillis();
            String cur = key(c);
            JSONArray ps = parents(c);
            for (int i = 0; i < olds.length(); i++) {
                JSONObject ok = olds.getJSONObject(i);
                if (ok.optLong("until") < now) continue;
                String old = ok.getString("key");
                for (int j = 0; j < ps.length(); j++) {
                    JSONObject par = ps.getJSONObject(j);
                    JSONObject m = new JSONObject();
                    m.put("type", "rekey");
                    m.put("ts", now);
                    m.put("to", par.getString("id"));
                    m.put("box", Keys.seal(par.getString("pub"), cur));
                    Ntfy.publish(Crypto.topic(old, "st"), Crypto.encrypt(old, m.toString()));
                }
            }
        } catch (Exception ignored) {
        }
    }
}
