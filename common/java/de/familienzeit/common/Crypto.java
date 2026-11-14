package de.familienzeit.common;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gemeinsamer Familienschlüssel (zufällige 256 Bit) pro Kinder-Handy.
 * Daraus werden die geheimen Kanalnamen bei ntfy.sh und die AES-Verschlüsselung abgeleitet.
 */
public final class Crypto {
    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final SecureRandom RNG = new SecureRandom();

    private Crypto() {}

    public static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    public static byte[] unb64(String s) {
        return Base64.decode(s.trim(), Base64.NO_WRAP);
    }

    public static String newKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return b64(k);
    }

    public static String newSecret() {
        byte[] k = new byte[18];
        RNG.nextBytes(k);
        return b64(k);
    }

    public static String sha(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : h) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Kanalname, z. B. "fz3a9f…-cfg". */
    public static String topic(String key, String suffix) {
        return "fz" + sha("fz-topic:" + key).substring(0, 24) + "-" + suffix;
    }

    /** 12-stelliger Notfall-Code (für die Freigabe direkt am Kinder-Handy). */
    public static String recoveryCode(String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(unb64(key), "HmacSHA256"));
            byte[] h = mac.doFinal("recovery".getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(ALPHABET.charAt((h[i] & 0xff) % ALPHABET.length()));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String normalize(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : code.toUpperCase().toCharArray()) {
            if (c == '-' || c == ' ') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    public static String pretty(String code) {
        String n = normalize(code);
        if (n.length() != 12) return n;
        return n.substring(0, 4) + "-" + n.substring(4, 8) + "-" + n.substring(8, 12);
    }

    static byte[] gcm(boolean enc, byte[] key, byte[] iv, byte[] data, int off, int len) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        return c.doFinal(data, off, len);
    }

    public static String encrypt(String key, String plain) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(plain.getBytes(StandardCharsets.UTF_8));
        }
        byte[] z = bos.toByteArray();
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        byte[] ct = gcm(true, unb64(key), iv, z, 0, z.length);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return b64(out);
    }

    /** Liefert null, wenn die Nachricht nicht mit diesem Schlüssel verschlüsselt wurde. */
    public static String decrypt(String key, String b64) {
        try {
            byte[] all = unb64(b64);
            if (all.length < 29) return null;
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            byte[] z = gcm(false, unb64(key), iv, all, 12, all.length - 12);
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(z))) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                return bos.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        }
    }
}
