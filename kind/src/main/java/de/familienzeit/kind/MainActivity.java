package de.familienzeit.kind;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Iterator;

import de.familienzeit.common.Crypto;

/** Die Oberfläche auf dem Kinder-Handy: Kopplung, eigene Bildschirmzeit, Notfall-Freigabe. */
public class MainActivity extends Activity {
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        root.removeAllViews();
        title("Familienzeit");

        if (!Policy.isOwner(this)) {
            text("Diese App ist noch nicht eingerichtet. Sie muss einmalig per USB vom PC aus "
                    + "eingerichtet werden (siehe Anleitung).");
            return;
        }

        if (Store.code(this).isEmpty()) {
            text("Gib den Kopplungscode ein, den die Eltern-App anzeigt:");
            final EditText et = new EditText(this);
            et.setHint("XXXX-XXXX-XXXX");
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            root.addView(et);
            button("Koppeln", () -> {
                String code = et.getText().toString();
                if (!Crypto.isValid(code)) {
                    Toast.makeText(this, "Code ungültig – bitte genau abtippen", Toast.LENGTH_LONG).show();
                    return;
                }
                Store.p(this).edit()
                        .putString("code", Crypto.normalize(code))
                        .putLong("lastCmdTs", System.currentTimeMillis())
                        .apply();
                Policy.applyBase(this, false);
                GuardService.start(this);
                render();
            });
            return;
        }

        GuardService.start(this);
        JSONObject cfg = Store.cfg(this);
        text(Store.p(this).getString("statusText", "Wird berechnet …"));

        section("Regeln");
        int daily = cfg.optInt("daily", 0);
        int net = cfg.optInt("net", 0);
        text("Bildschirmzeit pro Tag: " + (daily > 0 ? GuardService.hm(daily) : "unbegrenzt"));
        text("Internetzeit pro Tag: " + (net > 0 ? GuardService.hm(net) : "unbegrenzt"));
        if (cfg.optBoolean("nightOn", false)) {
            text("Nachtruhe: " + cfg.optString("nightFrom") + " – " + cfg.optString("nightTo") + " Uhr");
        }
        JSONObject limits = cfg.optJSONObject("limits");
        if (limits != null && limits.length() > 0) {
            StringBuilder sb = new StringBuilder("App-Limits:");
            Iterator<String> it = limits.keys();
            while (it.hasNext()) {
                String p = it.next();
                sb.append("\n• ").append(label(p)).append(": ").append(limits.optInt(p)).append(" Min.");
            }
            text(sb.toString());
        }
        text("Telefon, SMS und Notruf funktionieren immer.");

        section("Für Eltern");
        button("Notfall-Freigabe (Kopplungscode nötig)", this::emergencyRelease);
        String err = Store.p(this).getString("lastError", "");
        if (!err.isEmpty()) {
            TextView tv = text("Letzter Fehler: " + err);
            tv.setTextSize(11);
        }
        TextView v = text("Version " + BuildConfig.VERSION_NAME);
        v.setTextSize(11);
    }

    private void emergencyRelease() {
        final EditText et = new EditText(this);
        et.setHint("Kopplungscode");
        new AlertDialog.Builder(this)
                .setTitle("Kontrolle komplett aufheben?")
                .setMessage("Danach gelten keine Regeln mehr und die App kann deinstalliert werden.")
                .setView(et)
                .setPositiveButton("Aufheben", (d, w) -> {
                    if (Crypto.normalize(et.getText().toString()).equals(Store.code(this))) {
                        new Thread(() -> {
                            Policy.release(this);
                            runOnUiThread(this::render);
                        }).start();
                    } else {
                        Toast.makeText(this, "Falscher Code", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private String label(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ------------------------------------------------------------ kleine UI-Helfer

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void title(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(26);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(12));
        root.addView(tv);
    }

    private void section(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(20), 0, dp(6));
        root.addView(tv);
    }

    private TextView text(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(16);
        tv.setPadding(0, dp(4), 0, dp(4));
        root.addView(tv);
        return tv;
    }

    private void button(String s, Runnable r) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(bt, lp);
    }
}
