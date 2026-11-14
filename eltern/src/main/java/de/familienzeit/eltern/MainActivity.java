package de.familienzeit.eltern;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.familienzeit.common.Crypto;
import de.familienzeit.common.Updater;

/** Eltern-App: Status ansehen, Regeln ändern, Fernbefehle senden. */
public class MainActivity extends Activity {
    private LinearLayout root;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean visible;
    private int remoteParentVersion = -1;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            pollAsync();
            ui.postDelayed(this, 15_000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(60));
        sv.addView(root);
        setContentView(sv);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Sync.schedule(this);
        new Thread(() -> {
            int v = Updater.remoteVersion(BuildConfig.UPDATE_REPO);
            ui.post(() -> {
                remoteParentVersion = v;
                render();
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        render();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        ui.removeCallbacks(refresher);
    }

    private void pollAsync() {
        new Thread(() -> {
            try {
                if (Sync.poll(this, false)) ui.post(this::render);
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ================================================================ Darstellung

    private void render() {
        root.removeAllViews();
        title("Familienzeit");

        JSONObject st = Sync.json(this, "status");
        JSONObject cfg = Sync.json(this, "cfg");

        if (!st.has("ts")) {
            renderPairing();
            return;
        }

        // ---------------- Status
        long ago = (System.currentTimeMillis() - st.optLong("ts")) / 60000;
        section("Heute");
        small("Zuletzt gemeldet: " + (ago < 1 ? "gerade eben" : "vor " + agoText(ago)));
        int daily = cfg.optInt("daily", 0);
        int net = cfg.optInt("net", 0);
        int bonus = today().equals(cfg.optString("bonusDay")) ? cfg.optInt("bonus", 0) : 0;
        big("Bildschirmzeit: " + hm(st.optLong("screen")) + (daily > 0 ? " von " + hm(daily + bonus) : ""));
        text("Internetzeit: " + hm(st.optLong("net")) + (net > 0 ? " von " + hm(net + bonus) : ""));
        text("Entsperrt: " + st.optInt("unlocks") + "×");
        String lock = st.optString("lock", "");
        if (!lock.isEmpty()) colored("🔒 " + lock, Color.rgb(180, 60, 40));
        else if (st.optBoolean("netBlocked")) colored("🌐 Internet gesperrt", Color.rgb(180, 60, 40));
        if (!st.optBoolean("access", true)) {
            colored("⚠️ Der Nutzungszugriff wurde auf dem Kinder-Handy abgeschaltet. Das Handy ist deshalb gesperrt.",
                    Color.rgb(180, 60, 40));
        }
        if (st.optLong("cfgV") < cfg.optLong("v", 0)) {
            small("Neue Regeln sind noch nicht auf dem Kinder-Handy angekommen …");
        }

        row(btn("+15 Min", () -> addBonus(15)), btn("+30 Min", () -> addBonus(30)), btn("+60 Min", () -> addBonus(60)));
        boolean paused = cfg.optLong("pauseUntil", 0) > System.currentTimeMillis();
        if (paused) {
            small("Gesperrt bis " + time(cfg.optLong("pauseUntil")));
            row(btn("Sperre aufheben", () -> setPause(0)));
        } else {
            row(btn("Sperren 1 Std.", () -> setPause(60)), btn("Sperren bis morgen", () -> setPause(-1)));
        }
        row(btn("Jetzt aktualisieren", () -> cmd("refresh", "Kinder-Handy wird abgefragt …")));

        // ---------------- Standort
        section("Standort");
        final JSONObject loc = Sync.json(this, "loc");
        if (loc.has("lat")) {
            long locAgo = (System.currentTimeMillis() - loc.optLong("fix")) / 60000;
            text("Stand: " + (locAgo < 1 ? "gerade eben" : "vor " + agoText(locAgo))
                    + " · Genauigkeit ca. " + loc.optInt("acc") + " m");
            root.addView(btn("📍 In Google Maps öffnen", () -> {
                String q = loc.optDouble("lat") + "," + loc.optDouble("lon");
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=" + q)));
            }));
        }
        root.addView(btn("Standort jetzt abrufen", () -> cmd("locate",
                "Standort wird abgefragt – dauert bis zu 2 Minuten")));

        // ---------------- Meistgenutzte Apps
        JSONArray top = st.optJSONArray("top");
        if (top != null && top.length() > 0) {
            section("Meistgenutzt heute");
            for (int i = 0; i < top.length(); i++) {
                JSONArray r = top.optJSONArray(i);
                if (r == null) continue;
                text(r.optString(1) + ": " + hm(r.optLong(2)) + modeSuffix(cfg, r.optString(0)));
            }
        }

        // ---------------- Regeln
        section("Regeln");
        final EditText dailyEt = labeledInput("Bildschirmzeit pro Tag (Std:Min, leer = unbegrenzt)",
                daily > 0 ? hmShort(daily) : "");
        final EditText netEt = labeledInput("Internetzeit pro Tag (Std:Min, leer = unbegrenzt)",
                net > 0 ? hmShort(net) : "");
        final CheckBox nightCb = new CheckBox(this);
        nightCb.setText("Nachtruhe (Handy gesperrt)");
        nightCb.setChecked(cfg.optBoolean("nightOn", false));
        root.addView(nightCb);
        final EditText fromEt = labeledInput("von (Uhrzeit)", cfg.optString("nightFrom", "21:00"));
        final EditText toEt = labeledInput("bis (Uhrzeit)", cfg.optString("nightTo", "07:00"));
        root.addView(btn("Regeln speichern", () -> {
            try {
                JSONObject c = Sync.json(this, "cfg");
                c.put("daily", parseMin(dailyEt.getText().toString()));
                c.put("net", parseMin(netEt.getText().toString()));
                c.put("nightOn", nightCb.isChecked());
                c.put("nightFrom", normTime(fromEt.getText().toString(), "21:00"));
                c.put("nightTo", normTime(toEt.getText().toString(), "07:00"));
                send(c, "Regeln gespeichert");
            } catch (Exception e) {
                toast("Fehler: " + e.getMessage());
            }
        }));

        // ---------------- Apps
        renderApps(cfg, st);

        // ---------------- Verlauf
        JSONArray evs = Sync.events(this);
        if (evs.length() > 0) {
            section("Meldungen");
            for (int i = evs.length() - 1; i >= Math.max(0, evs.length() - 15); i--) {
                JSONObject e = evs.optJSONObject(i);
                if (e == null) continue;
                small(dateTime(e.optLong("ts")) + "  " + e.optString("text"));
            }
        }

        // ---------------- Wartung
        section("Wartung");
        small("Kinder-App Version " + st.optInt("ver") + " · Eltern-App Version " + BuildConfig.VERSION_CODE);
        small("Kopplungscode: " + Crypto.pretty(Sync.code(this)));
        if (remoteParentVersion > BuildConfig.VERSION_CODE) {
            root.addView(btn("Eltern-App aktualisieren (Version " + remoteParentVersion + ")", this::updateSelf));
        }
        root.addView(btn("Update auf dem Kinder-Handy jetzt installieren",
                () -> cmd("update", "Kinder-Handy sucht nach Updates …")));
        final boolean debug = cfg.optBoolean("debug", false);
        root.addView(btn(debug ? "USB-Debugging wieder sperren" : "USB-Debugging erlauben (für Wartung am PC)", () -> {
            try {
                JSONObject c = Sync.json(this, "cfg");
                c.put("debug", !debug);
                send(c, !debug ? "USB-Debugging erlaubt" : "USB-Debugging gesperrt");
            } catch (Exception e) {
                toast("Fehler: " + e.getMessage());
            }
        }));
        root.addView(btn("Kontrolle komplett aufheben …", this::confirmRelease));
    }

    private void renderPairing() {
        section("Kopplung");
        text("Gib diesen Code auf dem Handy deines Sohnes in der App „Familienzeit“ ein:");
        TextView code = new TextView(this);
        code.setText(Crypto.pretty(Sync.code(this)));
        code.setTextSize(30);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setGravity(Gravity.CENTER);
        code.setPadding(0, dp(16), 0, dp(16));
        code.setTextIsSelectable(true);
        root.addView(code);
        small("Der Code ist gleichzeitig der Schlüssel für die Verschlüsselung. Gib ihn nicht weiter.");
        text("Warte auf die erste Meldung vom Kinder-Handy …");
        root.addView(btn("Jetzt prüfen", this::pollAsync));
    }

    private void renderApps(JSONObject cfg, JSONObject st) {
        JSONObject appsMsg = Sync.json(this, "apps");
        JSONArray apps = appsMsg.optJSONArray("apps");
        if (apps == null || apps.length() == 0) return;
        Map<String, Long> usage = new HashMap<>();
        JSONArray top = st.optJSONArray("top");
        if (top != null) {
            for (int i = 0; i < top.length(); i++) {
                JSONArray r = top.optJSONArray(i);
                if (r != null) usage.put(r.optString(0), r.optLong(2));
            }
        }
        List<String[]> list = new ArrayList<>();
        for (int i = 0; i < apps.length(); i++) {
            JSONArray r = apps.optJSONArray(i);
            if (r != null) list.add(new String[]{r.optString(0), r.optString(1)});
        }
        Collections.sort(list, (a, b) -> a[1].compareToIgnoreCase(b[1]));

        Set<String> pending = new HashSet<>();
        JSONArray pend = appsMsg.optJSONArray("pending");
        if (pend != null) for (int i = 0; i < pend.length(); i++) pending.add(pend.optString(i));
        pending.removeAll(set(cfg, "approved"));
        pending.removeAll(set(cfg, "allowed"));
        pending.removeAll(set(cfg, "blocked"));
        JSONObject limObj = cfg.optJSONObject("limits");
        if (limObj != null) {
            java.util.Iterator<String> it = limObj.keys();
            while (it.hasNext()) pending.remove(it.next());
        }
        if (!pending.isEmpty()) {
            section("Neue Apps – warten auf Freigabe");
            for (final String[] a : list) {
                if (!pending.contains(a[0])) continue;
                colored(a[1], Color.rgb(180, 60, 40));
                row(btn("Freigeben", () -> setMode(a[0], 0, 0)),
                        btn("Mit Limit …", () -> askLimit(a[0], a[1])),
                        btn("Gesperrt lassen", () -> setMode(a[0], 2, 0)));
            }
        }

        section("Apps");
        small("Antippen, um ein Zeitlimit zu setzen, die App zu sperren oder immer zu erlauben. "
                + "„Immer erlaubt“ zählt nicht zur Bildschirmzeit und bleibt auch bei Sperre nutzbar.");
        for (final String[] a : list) {
            Long used = usage.get(a[0]);
            String line = a[1] + modeSuffix(cfg, a[0]) + (used != null ? "  ·  heute " + hm(used) : "");
            Button b = btn(line, () -> chooseMode(a[0], a[1]));
            b.setAllCaps(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            root.addView(b);
        }
    }

    private String modeSuffix(JSONObject cfg, String pkg) {
        if (set(cfg, "blocked").contains(pkg)) return "  [gesperrt]";
        if (set(cfg, "allowed").contains(pkg)) return "  [immer erlaubt]";
        JSONObject lim = cfg.optJSONObject("limits");
        if (lim != null && lim.optInt(pkg, 0) > 0) return "  [max. " + hm(lim.optInt(pkg)) + "]";
        return "";
    }

    private void chooseMode(final String pkg, String label) {
        String[] opts = {"Normal (zählt zur Bildschirmzeit)", "Zeitlimit pro Tag …", "Gesperrt", "Immer erlaubt"};
        new AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(opts, (d, which) -> {
                    if (which == 1) askLimit(pkg, label);
                    else setMode(pkg, which, 0);
                })
                .show();
    }

    private void askLimit(final String pkg, String label) {
        final EditText et = new EditText(this);
        et.setHint("z. B. 1:00 oder 45");
        et.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        new AlertDialog.Builder(this)
                .setTitle(label + ": Limit pro Tag")
                .setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    int m = parseMin(et.getText().toString());
                    if (m > 0) setMode(pkg, 1, m);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    /** 0 = normal, 1 = Limit, 2 = gesperrt, 3 = immer erlaubt */
    private void setMode(String pkg, int mode, int minutes) {
        try {
            JSONObject c = Sync.json(this, "cfg");
            Set<String> blocked = set(c, "blocked");
            Set<String> allowed = set(c, "allowed");
            Set<String> approved = set(c, "approved");
            JSONObject lim = c.optJSONObject("limits");
            if (lim == null) lim = new JSONObject();
            blocked.remove(pkg);
            allowed.remove(pkg);
            lim.remove(pkg);
            approved.add(pkg);
            if (mode == 1) lim.put(pkg, minutes);
            if (mode == 2) blocked.add(pkg);
            if (mode == 3) allowed.add(pkg);
            c.put("blocked", new JSONArray(blocked));
            c.put("allowed", new JSONArray(allowed));
            c.put("approved", new JSONArray(approved));
            c.put("limits", lim);
            send(c, "Gespeichert");
        } catch (Exception e) {
            toast("Fehler: " + e.getMessage());
        }
    }

    // ================================================================ Aktionen

    private void addBonus(int min) {
        try {
            JSONObject c = Sync.json(this, "cfg");
            int cur = today().equals(c.optString("bonusDay")) ? c.optInt("bonus", 0) : 0;
            c.put("bonusDay", today());
            c.put("bonus", cur + min);
            send(c, "+" + min + " Minuten für heute");
        } catch (Exception e) {
            toast("Fehler: " + e.getMessage());
        }
    }

    /** minutes > 0: so lange sperren, -1: bis morgen 6 Uhr, 0: aufheben */
    private void setPause(int minutes) {
        try {
            JSONObject c = Sync.json(this, "cfg");
            long until = 0;
            if (minutes > 0) until = System.currentTimeMillis() + minutes * 60_000L;
            if (minutes < 0) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
                cal.set(java.util.Calendar.HOUR_OF_DAY, 6);
                cal.set(java.util.Calendar.MINUTE, 0);
                until = cal.getTimeInMillis();
            }
            c.put("pauseUntil", until);
            send(c, until > 0 ? "Handy wird gesperrt" : "Sperre aufgehoben");
        } catch (Exception e) {
            toast("Fehler: " + e.getMessage());
        }
    }

    private void send(final JSONObject cfg, final String okMsg) {
        new Thread(() -> {
            try {
                Sync.sendCfg(this, cfg);
                ui.post(() -> {
                    toast(okMsg + " – wird innerhalb ca. 1 Minute übernommen");
                    render();
                });
            } catch (Exception e) {
                ui.post(() -> toast("Senden fehlgeschlagen: " + e.getMessage()));
            }
        }).start();
    }

    private void cmd(final String cmd, final String okMsg) {
        new Thread(() -> {
            try {
                Sync.sendCmd(this, cmd);
                ui.post(() -> toast(okMsg));
            } catch (Exception e) {
                ui.post(() -> toast("Senden fehlgeschlagen: " + e.getMessage()));
            }
        }).start();
    }

    private void confirmRelease() {
        new AlertDialog.Builder(this)
                .setTitle("Kontrolle komplett aufheben?")
                .setMessage("Alle Regeln und Sperren auf dem Kinder-Handy werden entfernt. Die App dort kann "
                        + "danach deinstalliert werden. Um die Kontrolle wieder einzurichten, ist erneut die "
                        + "Einrichtung per PC nötig.")
                .setPositiveButton("Ja, aufheben", (d, w) -> cmd("release", "Befehl gesendet"))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void updateSelf() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast("Bitte erlaube „Unbekannte Apps installieren“ für Familienzeit Eltern und tippe dann erneut.");
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        toast("Update wird geladen …");
        new Thread(() -> {
            String err = Updater.downloadAndInstall(this, BuildConfig.UPDATE_REPO, "eltern.apk",
                    remoteParentVersion, InstallReceiver.class);
            if (err != null) ui.post(() -> toast(err));
        }).start();
    }

    // ================================================================ Helfer

    private static Set<String> set(JSONObject o, String key) {
        Set<String> s = new HashSet<>();
        JSONArray a = o.optJSONArray(key);
        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(new Date());
    }

    private static String time(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(ms)) + " Uhr";
    }

    private static String dateTime(long ms) {
        return new SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(new Date(ms));
    }

    private static String agoText(long min) {
        if (min < 60) return min + " Min.";
        if (min < 48 * 60) return (min / 60) + " Std.";
        return (min / 1440) + " Tagen";
    }

    static String hm(long min) {
        return (min / 60) + ":" + String.format(Locale.GERMANY, "%02d", min % 60) + " h";
    }

    private static String hmShort(long min) {
        return (min / 60) + ":" + String.format(Locale.GERMANY, "%02d", min % 60);
    }

    /** "3:30" → 210, "45" → 45, leer → 0 */
    static int parseMin(String s) {
        s = s.trim().replace('.', ':').replace(',', ':');
        if (s.isEmpty()) return 0;
        try {
            if (s.contains(":")) {
                String[] p = s.split(":");
                return Integer.parseInt(p[0].trim()) * 60 + (p.length > 1 && !p[1].trim().isEmpty() ? Integer.parseInt(p[1].trim()) : 0);
            }
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String normTime(String s, String def) {
        int m = parseMin(s.contains(":") ? s : s + ":00");
        if (m <= 0 && !s.trim().startsWith("0")) return def;
        m = Math.min(m, 23 * 60 + 59);
        return String.format(Locale.GERMANY, "%02d:%02d", m / 60, m % 60);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void title(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(26);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tv);
    }

    private void section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(19);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(22), 0, dp(6));
        root.addView(tv);
    }

    private TextView text(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(16);
        tv.setPadding(0, dp(3), 0, dp(3));
        root.addView(tv);
        return tv;
    }

    private void big(String s) {
        TextView tv = text(s);
        tv.setTextSize(20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private void small(String s) {
        TextView tv = text(s);
        tv.setTextSize(13);
        tv.setTextColor(Color.GRAY);
    }

    private void colored(String s, int color) {
        TextView tv = text(s);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private EditText labeledInput(String label, String value) {
        small(label);
        EditText et = new EditText(this);
        et.setText(value);
        et.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        root.addView(et);
        return et;
    }

    private Button btn(String s, Runnable r) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setAllCaps(false);
        bt.setOnClickListener(v -> r.run());
        return bt;
    }

    private void row(View... views) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            l.addView(v, lp);
        }
        root.addView(l);
    }
}
