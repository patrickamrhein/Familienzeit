package de.familienzeit.common;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** QR-Codes zum Koppeln: enthalten Familienschlüssel und eine einmalige Einladung. */
public final class Qr {
    public static final String PREFIX = "FZ2:";

    private Qr() {}

    public static String payload(String key, String secret) {
        try {
            JSONObject o = new JSONObject();
            o.put("k", key);
            o.put("s", secret);
            return PREFIX + Crypto.b64(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** @return {Schlüssel, Einladung} oder null */
    public static String[] parse(String raw) {
        try {
            if (raw == null || !raw.startsWith(PREFIX)) return null;
            JSONObject o = new JSONObject(new String(Crypto.unb64(raw.substring(PREFIX.length())), StandardCharsets.UTF_8));
            String k = o.getString("k"), s = o.getString("s");
            if (Crypto.unb64(k).length != 32) return null;
            return new String[]{k, s};
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap bitmap(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }
}
