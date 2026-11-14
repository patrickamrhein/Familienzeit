package de.familienzeit.eltern;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
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
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.familienzeit.common.Crypto;
import de.familienzeit.common.Updater;

/** Eltern-App: Status ansehen, Regeln ändern, Fernbefehle senden. Geschützt per Fingerabdruck/PIN. */
public class MainActivity extends Activity {
    private static final long RELOCK_MS = 60_000;
    private static long unlockedUntil = 0;

    private Ui ui;
    private LinearLayout root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean visible;
    private boolean authRunning;
    private int remoteParentVersion = -1;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            if (unlocked()) pollAsync();
            main.postDelayed(this, 15_000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        getWindow().setStatusBarColor(ui.surface);
        getWindow().setNavigationBarColor(ui.surface);
        getWindow().getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ui.surface);
        sv.setFillViewport(true);
        root = ui.vbox();
        root.setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(48));
        sv.addView(root);
        setContentView(sv);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Sync.schedule(this);
        new Thread(() -> {
            int v = Updater.remoteVersion(BuildConfig.UPDATE_REPO);
            main.post(() -> {
                remoteParentVersion = v;
                if (unlocked()) render();
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        if (unlocked()) {
            render();
        } else {
            renderLocked();
            authenticate(this::render);
        }
        main.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        main.removeCallbacks(refresher);
        if (unlocked() && unlockedUntil != Long.MAX_VALUE) unlockedUntil = System.currentTimeMillis() + RELOCK_MS;
    }

    // ================================================================ Sperre

    private boolean unlocked() {
        return System.currentTimeMillis() < unlockedUntil;
    }

    private boolean deviceSecure() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        return km != null && km.isDeviceSecure();
    }

    /** Fragt Fingerabdruck, Gesicht oder Displaysperre ab. */
    private void authenticate(Runnable onSuccess) {
        if (!deviceSecure()) {
            Toast.makeText(this, "Tipp: Richte eine Displaysperre ein, damit die App geschützt ist.",
                    Toast.LENGTH_LONG).show();
            unlockedUntil = Long.MAX_VALUE;
            onSuccess.run();
            return;
        }
        if (authRunning) return;
        authRunning = true;
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                .setTitle("Familienzeit entsperren")
                .setSubtitle("Mit Fingerabdruck, Gesicht oder PIN");
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            b.setDeviceCredentialAllowed(true);
        }
        b.build().authenticate(new CancellationSignal(), getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        authRunning = false;
                        unlockedUntil = System.currentTimeMillis() + 24 * 3600_000L;
                        onSuccess.run();
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        authRunning = false;
                    }
                });
    }

    /** Für heikle Aktionen (Code anzeigen, Kontrolle aufheben) immer erneut fragen. */
    private void reauth(Runnable onSuccess) {
        if (!deviceSecure()) {
            onSuccess.run();
            return;
        }
        final long keep = unlockedUntil;
        authenticate(() -> {
            unlockedUntil = keep;
            onSuccess.run();
        });
    }

    private void renderLocked() {
        root.removeAllViews();
        LinearLayout box = ui.vbox();
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, ui.dp(140), 0, 0);
        TextView icon = ui.text(box, "🔒", 48, ui.onSurface, false);
        icon.setGravity(Gravity.CENTER);
        TextView t = ui.text(box, "Familienzeit ist gesperrt", 20, ui.onSurface, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ui.dp(12), 0, ui.dp(20));
        box.addView(ui.pill("Entsperren", true, () -> authenticate(this::render)),
                new LinearLayout.LayoutParams(ui.dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void pollAsync() {
        new Thread(() -> {
            try {
                if (Sync.poll(this, false)) main.post(() -> {
                    if (unlocked() && visible) render();
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ================================================================ Darstellung

    private void render() {
        root.removeAllViews();
        JSONObject st = Sync.json(this, "status");
        JSONObject cfg = Sync.json(this, "cfg");

        TextView h = ui.text(root, "Familienzeit", 28, ui.onSurface, true);
        h.setPadding(ui.dp(4), 0, 0, ui.dp(2));

        if (!st.has("ts")) {
            renderPairing();
            return;
        }
        long ago = (System.currentTimeMillis() - st.optLong("ts")) / 60000;
        TextView sub = ui.hint(root, "Zuletzt gemeldet " + (ago < 1 ? "gerade eben" : "vor " + agoText(ago)));
        sub.setPadding(ui.dp(4), 0, 0, ui.dp(14));

        renderToday(st, cfg);
        renderPending(cfg);
        renderTop(st, cfg);
        renderLocation();
        renderRules(cfg);
        renderApps(cfg, st);
        renderEvents();
        renderMaintenance(st, cfg);
    }

    private void renderToday(JSONObject st, JSONObject cfg) {
        LinearLayout c = ui.card(root);
        int daily = cfg.optInt("daily", 0);
        int net = cfg.optInt("net", 0);
        int bonus = today().equals(cfg.optString("bonusDay")) ? cfg.optInt("bonus", 0) : 0;
        long screen = st.optLong("screen");
        String lock = st.optString("lock", "");

        LinearLayout top = ui.hbox();
        Ui.Ring ring = new Ui.Ring(ui);
        float frac = daily > 0 ? screen / (float) (daily + bonus) : 0;
        int ringColor = frac >= 1 ? ui.danger : frac >= 0.8f ? android.graphics.Color.rgb(199, 125, 0) : ui.primary;
        ring.set(frac, hmShort(screen), daily > 0 ? "von " + hmShort(daily + bonus) + " Std." : "Bildschirmzeit", ringColor);
        top.addView(ring, new LinearLayout.LayoutParams(ui.dp(140), ui.dp(140)));

        LinearLayout stats = ui.vbox();
        stats.setPadding(ui.dp(18), 0, 0, 0);
        stat(stats, "Internet", hm(st.optLong("net")) + (net > 0 ? " / " + hmShort(net + bonus) : ""));
        stat(stats, "Entsperrt", st.optInt("unlocks") + "×");
        if (bonus > 0) stat(stats, "Bonus heute", "+" + bonus + " Min.");
        top.addView(stats, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(top);

        if (!st.optBoolean("access", true)) {
            ui.banner(c, "⚠️ Der Nutzungszugriff wurde abgeschaltet – das Handy ist deshalb gesperrt.",
                    ui.dangerContainer, ui.danger);
        } else if (!lock.isEmpty()) {
            ui.banner(c, "🔒 Gesperrt: " + lock, ui.dangerContainer, ui.danger);
        } else if (st.optBoolean("netBlocked")) {
            ui.banner(c, "🌐 Internet gesperrt", ui.dangerContainer, ui.danger);
        } else {
            ui.banner(c, "✓ Handy frei nutzbar", ui.successContainer, ui.success);
        }
        if (st.optLong("cfgV") < cfg.optLong("v", 0)) {
            ui.hint(c, "Neue Regeln sind noch unterwegs zum Kinder-Handy …").setPadding(0, ui.dp(8), 0, 0);
        }

        ui.row(c, ui.pill("+15 Min", false, () -> addBonus(15)),
                ui.pill("+30 Min", false, () -> addBonus(30)),
                ui.pill("+1 Std", false, () -> addBonus(60)));
        boolean paused = cfg.optLong("pauseUntil", 0) > System.currentTimeMillis();
        if (paused) {
            ui.hint(c, "Gesperrt bis " + time(cfg.optLong("pauseUntil"))).setPadding(0, ui.dp(8), 0, 0);
            ui.full(c, ui.pill("Sperre aufheben", true, () -> setPause(0)));
        } else {
            ui.row(c, ui.pill("Sperren 1 Std", true, () -> setPause(60)),
                    ui.pill("Bis morgen sperren", true, () -> setPause(-1)));
        }
        ui.full(c, ui.pill("↻  Jetzt aktualisieren", false, () -> cmd("refresh", "Kinder-Handy wird abgefragt …")));
    }

    private void stat(LinearLayout p, String label, String value) {
        ui.hint(p, label);
        TextView v = ui.text(p, value, 18, ui.onSurface, true);
        v.setPadding(0, 0, 0, ui.dp(6));
    }

    private void renderPending(JSONObject cfg) {
        JSONArray pend = Sync.json(this, "apps").optJSONArray("pending");
        if (pend == null || pend.length() == 0) return;
        Set<String> pending = new HashSet<>();
        for (int i = 0; i < pend.length(); i++) pending.add(pend.optString(i));
        pending.removeAll(mentioned(cfg));
        if (pending.isEmpty()) return;
        Map<String, String> labels = labels();
        LinearLayout c = ui.card(root);
        ui.cardTitle(c, "📲 Neue Apps warten auf Freigabe");
        for (final String p : pending) {
            final String label = labels.containsKey(p) ? labels.get(p) : p;
            ui.listRow(c, label, "Gesperrt, bis du entscheidest", -1, 0, null);
            ui.row(c, ui.pill("Freigeben", true, () -> setMode(p, 0, 0)),
                    ui.pill("Mit Limit", false, () -> askLimit(p, label)),
                    ui.dangerPill("Sperren", () -> setMode(p, 2, 0)));
        }
    }

    private void renderTop(JSONObject st, JSONObject cfg) {
        JSONArray top = st.optJSONArray("top");
        if (top == null || top.length() == 0) return;
        LinearLayout c = ui.card(root);
        ui.cardTitle(c, "Meistgenutzt heute");
        long max = 1;
        for (int i = 0; i < top.length(); i++) {
            JSONArray r = top.optJSONArray(i);
            if (r != null) max = Math.max(max, r.optLong(2));
        }
        for (int i = 0; i < Math.min(8, top.length()); i++) {
            JSONArray r = top.optJSONArray(i);
            if (r == null) continue;
            final String pkg = r.optString(0), label = r.optString(1);
            String mode = modeText(cfg, pkg);
            ui.listRow(c, label, hm(r.optLong(2)) + (mode.isEmpty() ? "" : "  ·  " + mode),
                    r.optLong(2) / (float) max, ui.primary, () -> chooseMode(pkg, label));
        }
    }

    private void renderLocation() {
        LinearLayout c = ui.card(root);
        ui.cardTitle(c, "📍 Standort");
        final JSONObject loc = Sync.json(this, "loc");
        if (loc.has("lat")) {
            long locAgo = (System.currentTimeMillis() - loc.optLong("fix")) / 60000;
            ui.body(c, "Stand " + (locAgo < 1 ? "gerade eben" : "vor " + agoText(locAgo))
                    + " · Genauigkeit ca. " + loc.optInt("acc") + " m");
            ui.row(c, ui.pill("In Google Maps öffnen", true, () -> {
                        String q = loc.optDouble("lat") + "," + loc.optDouble("lon");
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://www.google.com/maps/search/?api=1&query=" + q)));
                    }),
                    ui.pill("Neu abrufen", false, this::locate));
        } else {
            ui.hint(c, "Noch kein Standort abgerufen.");
            ui.full(c, ui.pill("Standort abrufen", true, this::locate));
        }
    }

    private void locate() {
        cmd("locate", "Standort wird abgefragt – dauert bis zu 2 Minuten");
    }

    private void renderRules(JSONObject cfg) {
        LinearLayout c = ui.card(root);
        ui.cardTitle(c, "Regeln");
        int daily = cfg.optInt("daily", 0);
        int net = cfg.optInt("net", 0);
        int timeType = InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME;
        final EditText dailyEt = ui.input(c, "Bildschirmzeit pro Tag (Std:Min, leer = unbegrenzt)",
                daily > 0 ? hmShort(daily) : "", timeType);
        final EditText netEt = ui.input(c, "Internetzeit pro Tag (Std:Min, leer = unbegrenzt)",
                net > 0 ? hmShort(net) : "", timeType);
        final CheckBox nightCb = new CheckBox(this);
        nightCb.setText("Nachtruhe – Handy gesperrt");
        nightCb.setTextSize(15);
        nightCb.setTextColor(ui.onSurface);
        nightCb.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.primary));
        nightCb.setChecked(cfg.optBoolean("nightOn", false));
        c.addView(nightCb);
        LinearLayout times = ui.hbox();
        LinearLayout l = ui.vbox();
        LinearLayout r = ui.vbox();
        final EditText fromEt = ui.input(l, "von", cfg.optString("nightFrom", "21:00"), timeType);
        final EditText toEt = ui.input(r, "bis", cfg.optString("nightTo", "07:00"), timeType);
        times.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = ui.dp(12);
        times.addView(r, rp);
        c.addView(times);
        ui.full(c, ui.pill("Regeln speichern", true, () -> {
            try {
                JSONObject n = Sync.json(this, "cfg");
                n.put("daily", parseMin(dailyEt.getText().toString()));
                n.put("net", parseMin(netEt.getText().toString()));
                n.put("nightOn", nightCb.isChecked());
                n.put("nightFrom", normTime(fromEt.getText().toString(), "21:00"));
                n.put("nightTo", normTime(toEt.getText().toString(), "07:00"));
                send(n, "Regeln gespeichert");
            } catch (Exception e) {
                toast("Fehler: " + e.getMessage());
            }
        }));
    }

    private void renderApps(JSONObject cfg, JSONObject st) {
        JSONArray apps = Sync.json(this, "apps").optJSONArray("apps");
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

        LinearLayout c = ui.card(root);
        ui.cardTitle(c, "Alle Apps");
        ui.hint(c, "Antippen für Zeitlimit, Sperre oder „immer erlaubt“. Immer erlaubte Apps zählen nicht "
                + "zur Bildschirmzeit und bleiben auch bei Sperre nutzbar.").setPadding(0, 0, 0, ui.dp(6));
        for (final String[] a : list) {
            Long used = usage.get(a[0]);
            String mode = modeText(cfg, a[0]);
            String subText = (mode.isEmpty() ? "Normal" : mode) + (used != null ? "  ·  heute " + hm(used) : "");
            ui.listRow(c, a[1], subText, -1, 0, () -> chooseMode(a[0], a[1]));
        }
    }

    private void renderEvents() {
        JSONArray evs = Sync.events(this);
        if (evs.length() == 0) return;
        LinearLayout c = ui.card(root);
        ui.cardTitle(c, "Meldungen");
        for (int i = evs.length() - 1; i >= Math.max(0, evs.length() - 12); i--) {
            JSONObject e = evs.optJSONObject(i);
            if (e == null) continue;
            ui.body(c, e.optString("text"));
            TextView t = ui.hint(c, dateTime(e.optLong("ts")));
            t.setPadding(0, 0, 0, ui.dp(8));
        }
    }

    private void renderMaintenance(JSONObject st, JSONObject cfg) {
        LinearLayout c = ui.card(root);
        ui.cardTitle(c, "Wartung");
        ui.hint(c, "Kinder-App Version " + st.optInt("ver") + " · Eltern-App Version " + BuildConfig.VERSION_CODE);
        if (remoteParentVersion > BuildConfig.VERSION_CODE) {
            ui.full(c, ui.pill("Eltern-App aktualisieren (Version " + remoteParentVersion + ")", true, this::updateSelf));
        }
        ui.full(c, ui.pill("Update auf dem Kinder-Handy installieren", false,
                () -> cmd("update", "Kinder-Handy sucht nach Updates …")));
        final boolean debug = cfg.optBoolean("debug", false);
        ui.full(c, ui.pill(debug ? "USB-Debugging wieder sperren" : "USB-Debugging erlauben (Wartung am PC)", false, () -> {
            try {
                JSONObject n = Sync.json(this, "cfg");
                n.put("debug", !debug);
                send(n, !debug ? "USB-Debugging erlaubt" : "USB-Debugging gesperrt");
            } catch (Exception e) {
                toast("Fehler: " + e.getMessage());
            }
        }));
        ui.full(c, ui.pill("Kopplungscode anzeigen", false, () -> reauth(this::showCode)));
        ui.full(c, ui.dangerPill("Kontrolle komplett aufheben …", () -> reauth(this::confirmRelease)));
    }

    private void showCode() {
        TextView tv = new TextView(this);
        tv.setText(Crypto.pretty(Sync.code(this)));
        tv.setTextSize(26);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, ui.dp(20), 0, ui.dp(8));
        tv.setTextIsSelectable(true);
        new AlertDialog.Builder(this)
                .setTitle("Kopplungscode")
                .setMessage("Der Code ist der Schlüssel zu allen Daten. Nicht weitergeben.")
                .setView(tv)
                .setPositiveButton("Schließen", null)
                .show();
    }

    private void renderPairing() {
        TextView sub = ui.hint(root, "Einrichtung");
        sub.setPadding(ui.dp(4), 0, 0, ui.dp(14));
        LinearLayout c = ui.card(root);
        ui.cardTitle(c, "Kopplung");
        ui.body(c, "Gib diesen Code auf dem Handy deines Sohnes in der App „Familienzeit“ ein:");
        TextView code = new TextView(this);
        code.setText(Crypto.pretty(Sync.code(this)));
        code.setTextSize(28);
        code.setTextColor(ui.onPrimaryContainer);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setGravity(Gravity.CENTER);
        code.setBackground(ui.rounded(ui.primaryContainer, 16));
        code.setPadding(0, ui.dp(18), 0, ui.dp(18));
        code.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(12);
        lp.bottomMargin = ui.dp(12);
        c.addView(code, lp);
        ui.hint(c, "Der Code ist gleichzeitig der Schlüssel für die Verschlüsselung. Gib ihn nicht weiter.");
        ui.body(c, "Warte auf die erste Meldung vom Kinder-Handy …").setPadding(0, ui.dp(12), 0, 0);
        ui.full(c, ui.pill("Jetzt prüfen", true, this::pollAsync));
    }

    // ================================================================ App-Regeln

    private Set<String> mentioned(JSONObject cfg) {
        Set<String> s = new HashSet<>(set(cfg, "approved"));
        s.addAll(set(cfg, "allowed"));
        s.addAll(set(cfg, "blocked"));
        JSONObject lim = cfg.optJSONObject("limits");
        if (lim != null) {
            Iterator<String> it = lim.keys();
            while (it.hasNext()) s.add(it.next());
        }
        return s;
    }

    private Map<String, String> labels() {
        Map<String, String> m = new HashMap<>();
        JSONArray apps = Sync.json(this, "apps").optJSONArray("apps");
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                JSONArray r = apps.optJSONArray(i);
                if (r != null) m.put(r.optString(0), r.optString(1));
            }
        }
        return m;
    }

    private String modeText(JSONObject cfg, String pkg) {
        if (set(cfg, "blocked").contains(pkg)) return "Gesperrt";
        if (set(cfg, "allowed").contains(pkg)) return "Immer erlaubt";
        JSONObject lim = cfg.optJSONObject("limits");
        if (lim != null && lim.optInt(pkg, 0) > 0) return "Limit " + hm(lim.optInt(pkg));
        return "";
    }

    private void chooseMode(final String pkg, final String label) {
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
                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, 1);
                cal.set(Calendar.HOUR_OF_DAY, 6);
                cal.set(Calendar.MINUTE, 0);
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
                main.post(() -> {
                    toast(okMsg + " – wird innerhalb ca. 1 Minute übernommen");
                    render();
                });
            } catch (Exception e) {
                main.post(() -> toast("Senden fehlgeschlagen: " + e.getMessage()));
            }
        }).start();
    }

    private void cmd(final String cmd, final String okMsg) {
        new Thread(() -> {
            try {
                Sync.sendCmd(this, cmd);
                main.post(() -> toast(okMsg));
            } catch (Exception e) {
                main.post(() -> toast("Senden fehlgeschlagen: " + e.getMessage()));
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
        if (!getPackageManager().canRequestPackageInstalls()) {
            toast("Bitte erlaube „Unbekannte Apps installieren“ für Familienzeit Eltern und tippe dann erneut.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        toast("Update wird geladen …");
        new Thread(() -> {
            String err = Updater.downloadAndInstall(this, BuildConfig.UPDATE_REPO, "eltern.apk",
                    remoteParentVersion, InstallReceiver.class);
            if (err != null) main.post(() -> toast(err));
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
        if (min < 60) return min + " Min.";
        return (min / 60) + ":" + String.format(Locale.GERMANY, "%02d", min % 60) + " Std.";
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
                return Integer.parseInt(p[0].trim()) * 60
                        + (p.length > 1 && !p[1].trim().isEmpty() ? Integer.parseInt(p[1].trim()) : 0);
            }
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String normTime(String s, String def) {
        String t = s.trim();
        if (t.isEmpty()) return def;
        int m = parseMin(t.contains(":") || t.contains(".") ? t : t + ":00");
        m = Math.max(0, Math.min(m, 23 * 60 + 59));
        return String.format(Locale.GERMANY, "%02d:%02d", m / 60, m % 60);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
