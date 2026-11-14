package de.familienzeit.kind;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Iterator;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

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
            Store.p(this).edit()
                    .putString("code", Crypto.newCode())
                    .putLong("lastCmdTs", System.currentTimeMillis())
                    .apply();
        }
        GuardService.start(this);

        if (!Store.p(this).getBoolean("paired", false)) {
            text("Scanne diesen QR-Code mit der App „Familienzeit Eltern“:");
            ImageView qr = new ImageView(this);
            qr.setImageBitmap(qrBitmap("FZ1:" + Store.code(this), dp(260)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(260), dp(260));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(16);
            lp.bottomMargin = dp(16);
            root.addView(qr, lp);
            TextView code = text(Crypto.pretty(Store.code(this)));
            code.setTextSize(22);
            code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            code.setGravity(Gravity.CENTER);
            text("Ohne Kamera kannst du den Code auch in der Eltern-App eintippen. "
                    + "Der Code ist der Schlüssel zu allen Daten – nicht abfotografieren lassen. "
                    + "Nach der Kopplung wird er hier nicht mehr angezeigt.");
            button("Prüfen, ob gekoppelt", this::render);
            root.postDelayed(() -> {
                if (!isFinishing() && !Store.p(this).getBoolean("paired", false)) render();
            }, 5000);
            return;
        }

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

    private static Bitmap qrBitmap(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            }
            return bmp;
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565);
        }
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
