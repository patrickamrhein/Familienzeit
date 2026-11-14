package de.familienzeit.kind;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Lokaler Speicher der Kinder-App. */
final class Store {
    private Store() {}

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("fz", Context.MODE_PRIVATE);
    }

    static String code(Context c) {
        return p(c).getString("code", "");
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(new Date());
    }

    /** Aktuelle Regeln der Eltern (leer = keine Grenzen). */
    static JSONObject cfg(Context c) {
        try {
            return new JSONObject(p(c).getString("cfg", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void saveCfg(Context c, JSONObject cfg) {
        p(c).edit().putString("cfg", cfg.toString()).apply();
    }

    static Set<String> set(JSONObject o, String key) {
        Set<String> s = new HashSet<>();
        JSONArray a = o.optJSONArray(key);
        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    static Set<String> strings(Context c, String key) {
        return new HashSet<>(p(c).getStringSet(key, new HashSet<>()));
    }

    static void putStrings(Context c, String key, Set<String> v) {
        p(c).edit().putStringSet(key, new HashSet<>(v)).apply();
    }

    /** Merkt sich pro Tag, welche Hinweise schon verschickt wurden. */
    static boolean firstTimeToday(Context c, String key) {
        String day = today();
        SharedPreferences sp = p(c);
        if (!day.equals(sp.getString("noteDay", ""))) {
            sp.edit().putString("noteDay", day).putStringSet("notes", new HashSet<>()).apply();
        }
        Set<String> notes = strings(c, "notes");
        if (notes.contains(key)) return false;
        notes.add(key);
        putStrings(c, "notes", notes);
        return true;
    }

    static boolean netBlocked(Context c) {
        return p(c).getBoolean("netBlocked", false);
    }
}
