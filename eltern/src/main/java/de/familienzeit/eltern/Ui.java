package de.familienzeit.eltern;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Kleine Bausteine für eine Oberfläche im Stil von Material 3 – ohne zusätzliche Bibliotheken. */
final class Ui {
    final Context c;
    final int primary, onPrimary, primaryContainer, onPrimaryContainer;
    final int surface, card, onSurface, muted, outline;
    final int danger, dangerContainer, success, successContainer;

    Ui(Context c) {
        this.c = c;
        if (Build.VERSION.SDK_INT >= 31) {
            primary = col(android.R.color.system_accent1_600);
            primaryContainer = col(android.R.color.system_accent1_100);
            onPrimaryContainer = col(android.R.color.system_accent1_900);
            surface = col(android.R.color.system_neutral1_50);
            outline = col(android.R.color.system_neutral2_200);
        } else {
            primary = Color.rgb(63, 90, 169);
            primaryContainer = Color.rgb(219, 225, 255);
            onPrimaryContainer = Color.rgb(0, 23, 75);
            surface = Color.rgb(242, 243, 250);
            outline = Color.rgb(197, 198, 208);
        }
        onPrimary = Color.WHITE;
        card = Color.WHITE;
        onSurface = Color.rgb(27, 27, 31);
        muted = Color.rgb(95, 99, 104);
        danger = Color.rgb(179, 38, 30);
        dangerContainer = Color.rgb(249, 222, 220);
        success = Color.rgb(20, 108, 46);
        successContainer = Color.rgb(196, 238, 208);
    }

    private int col(int id) {
        return c.getColor(id);
    }

    int dp(float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    LinearLayout vbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout hbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Weiße Karte mit runden Ecken. */
    LinearLayout card(ViewGroup parent) {
        LinearLayout l = vbox();
        l.setBackground(rounded(card, 24));
        l.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(l, lp);
        return l;
    }

    TextView text(ViewGroup p, String s, float size, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.addView(tv);
        return tv;
    }

    TextView cardTitle(ViewGroup p, String s) {
        TextView tv = text(p, s, 17, onSurface, true);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    TextView body(ViewGroup p, String s) {
        TextView tv = text(p, s, 15, onSurface, false);
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    TextView hint(ViewGroup p, String s) {
        TextView tv = text(p, s, 13, muted, false);
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    /** Farbiger Hinweis-Streifen, z. B. "Gesperrt: Nachtruhe". */
    TextView banner(ViewGroup p, String s, int bg, int fg) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(14);
        tv.setTextColor(fg);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setBackground(rounded(bg, 12));
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        p.addView(tv, lp);
        return tv;
    }

    /** Pillenförmiger Knopf. filled = Hauptaktion, sonst getönt. */
    Button pill(String s, boolean filled, Runnable r) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setPadding(dp(16), 0, dp(16), 0);
        int bg = filled ? primary : primaryContainer;
        b.setTextColor(filled ? onPrimary : onPrimaryContainer);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), rounded(bg, 100), null));
        b.setOnClickListener(v -> r.run());
        return b;
    }

    Button dangerPill(String s, Runnable r) {
        Button b = pill(s, false, r);
        b.setTextColor(danger);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), rounded(dangerContainer, 100), null));
        return b;
    }

    /** Knöpfe gleichmäßig nebeneinander. */
    LinearLayout row(ViewGroup p, View... views) {
        LinearLayout l = hbox();
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            l.addView(views[i], lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        p.addView(l, lp);
        return l;
    }

    void full(ViewGroup p, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        p.addView(v, lp);
    }

    EditText input(ViewGroup p, String label, String value, int inputType) {
        hint(p, label);
        EditText et = new EditText(c);
        et.setText(value);
        et.setInputType(inputType);
        et.setTextSize(16);
        et.setBackground(rounded(surface, 12));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        p.addView(et, lp);
        return et;
    }

    /** Runder Buchstabe als App-Symbol. */
    TextView avatar(String label) {
        TextView tv = new TextView(c);
        String l = label == null || label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase();
        tv.setText(l);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(16);
        tv.setTextColor(onPrimaryContainer);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(primaryContainer);
        tv.setBackground(g);
        return tv;
    }

    /** Zeile mit Symbol, Titel, Untertitel und optionalem Balken. */
    LinearLayout listRow(ViewGroup p, String title, String sub, float barFraction, int barColor, Runnable onClick) {
        LinearLayout row = hbox();
        row.setPadding(0, dp(8), 0, dp(8));
        TextView av = avatar(title);
        row.addView(av, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout col = vbox();
        col.setPadding(dp(12), 0, 0, 0);
        TextView t = text(col, title, 15, onSurface, true);
        t.setSingleLine(true);
        if (sub != null && !sub.isEmpty()) text(col, sub, 13, muted, false);
        if (barFraction >= 0) {
            FrameLayout track = new FrameLayout(c);
            track.setBackground(rounded(surface, 4));
            View bar = new View(c);
            bar.setBackground(rounded(barColor, 4));
            track.addView(bar, new FrameLayout.LayoutParams(0, dp(6)));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
            tlp.topMargin = dp(5);
            col.addView(track, tlp);
            final float f = Math.max(0.02f, Math.min(1f, barFraction));
            track.post(() -> {
                ViewGroup.LayoutParams lp = bar.getLayoutParams();
                lp.width = Math.round(track.getWidth() * f);
                bar.setLayoutParams(lp);
            });
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (onClick != null) {
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), null, rounded(Color.WHITE, 12)));
            row.setOnClickListener(v -> onClick.run());
        }
        p.addView(row);
        return row;
    }

    /** Fortschrittsring für die Bildschirmzeit. */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;
        private String center = "", sub = "";

        Ring(Ui ui) {
            super(ui.c);
            float stroke = ui.dp(12);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(ui.surface);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(ui.primary);
            big.setTextAlign(Paint.Align.CENTER);
            big.setTextSize(ui.dp(26));
            big.setColor(ui.onSurface);
            big.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            small.setTextAlign(Paint.Align.CENTER);
            small.setTextSize(ui.dp(12));
            small.setColor(ui.muted);
        }

        void set(float fraction, String center, String sub, int color) {
            this.fraction = Math.max(0, Math.min(1, fraction));
            this.center = center;
            this.sub = sub;
            arc.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight());
            float pad = track.getStrokeWidth() / 2 + 2;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            r.set(cx - s / 2 + pad, cy - s / 2 + pad, cx + s / 2 - pad, cy + s / 2 - pad);
            cv.drawArc(r, 0, 360, false, track);
            if (fraction > 0) cv.drawArc(r, -90, 360 * fraction, false, arc);
            cv.drawText(center, cx, cy + big.getTextSize() * 0.25f, big);
            cv.drawText(sub, cx, cy + big.getTextSize() * 0.25f + small.getTextSize() * 1.6f, small);
        }
    }
}
