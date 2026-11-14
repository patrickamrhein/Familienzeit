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
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Kopplungscode, Themen-Namen und Verschlüsselung.
 * Aus dem 12-stelligen Kopplungscode werden abgeleitet:
 *  - der geheime Kanalname bei ntfy.sh
 *  - ein AES-256-Schlüssel (alle Nachrichten sind Ende-zu-Ende verschlüsselt)
 */
public final class Crypto {
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();

    private static String cachedCode;
    private static byte[] cachedKey;

    private Crypto() {}

    public static String newCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** Großbuchstaben, ohne Leer- und Trennzeichen. */
    public static String normalize(String code) {
        if (code == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : code.toUpperCase().toCharArray()) {
            if (c == '-' || c == ' ') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    public static boolean isValid(String code) {
        String n = normalize(code);
        if (n.length() != 12) return false;
        for (char c : n.toCharArray()) if (ALPHABET.indexOf(c) < 0) return false;
        return true;
    }

    public static String pretty(String code) {
        String n = normalize(code);
        if (n.length() != 12) return n;
        return n.substring(0, 4) + "-" + n.substring(4, 8) + "-" + n.substring(8, 12);
    }

    /** Kanalname, z. B. "fz-3a9f...-cfg". */
    public static String topic(String code, String suffix) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("fz-topic:" + normalize(code)).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("fz");
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", h[i]));
            return sb + "-" + suffix;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static synchronized byte[] key(String code) throws Exception {
        String n = normalize(code);
        if (n.equals(cachedCode) && cachedKey != null) return cachedKey;
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(n.toCharArray(), "familienzeit-v1".getBytes(StandardCharsets.UTF_8), 120000, 256);
        cachedKey = f.generateSecret(spec).getEncoded();
        cachedCode = n;
        return cachedKey;
    }

    public static String encrypt(String code, String plain) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(plain.getBytes(StandardCharsets.UTF_8));
        }
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(code), "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(bos.toByteArray());
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    /** Liefert null, wenn die Nachricht nicht mit diesem Code verschlüsselt wurde. */
    public static String decrypt(String code, String b64) {
        try {
            byte[] all = Base64.decode(b64.trim(), Base64.NO_WRAP);
            if (all.length < 29) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(code), "AES"), new GCMParameterSpec(128, all, 0, 12));
            byte[] gz = c.doFinal(all, 12, all.length - 12);
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
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
