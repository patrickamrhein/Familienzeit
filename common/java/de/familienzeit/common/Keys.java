package de.familienzeit.common;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.KeyAgreement;

import org.json.JSONObject;

/**
 * Persönliche Schlüssel jedes Eltern-Handys (Elliptische Kurve P-256).
 * Damit werden Befehle unterschrieben, und das Kinder-Handy kann neue
 * Familienschlüssel gezielt an einzelne Eltern-Handys verschlüsseln.
 */
public final class Keys {
    private Keys() {}

    /** @return {privat, öffentlich} jeweils Base64 */
    public static String[] generate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), Crypto.RNG);
        KeyPair kp = g.generateKeyPair();
        return new String[]{Crypto.b64(kp.getPrivate().getEncoded()), Crypto.b64(kp.getPublic().getEncoded())};
    }

    static PrivateKey priv(String b64) throws Exception {
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Crypto.unb64(b64)));
    }

    static PublicKey pub(String b64) throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Crypto.unb64(b64)));
    }

    /** Kurze Kennung eines Eltern-Handys. */
    public static String id(String pubB64) {
        return Crypto.sha("fz-id:" + pubB64).substring(0, 16);
    }

    public static String sign(String privB64, String data) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(priv(privB64));
        s.update(data.getBytes(StandardCharsets.UTF_8));
        return Crypto.b64(s.sign());
    }

    public static boolean verify(String pubB64, String data, String sigB64) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pub(pubB64));
            s.update(data.getBytes(StandardCharsets.UTF_8));
            return s.verify(Crypto.unb64(sigB64));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] agree(PrivateKey a, PublicKey b) throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(a);
        ka.doPhase(b, true);
        return MessageDigest.getInstance("SHA-256").digest(ka.generateSecret());
    }

    /** Verschlüsselt einen Text so, dass nur der Besitzer von {@code pubB64} ihn lesen kann. */
    public static String seal(String pubB64, String plain) throws Exception {
        String[] eph = generate();
        byte[] key = agree(priv(eph[0]), pub(pubB64));
        byte[] iv = new byte[12];
        Crypto.RNG.nextBytes(iv);
        byte[] p = plain.getBytes(StandardCharsets.UTF_8);
        byte[] ct = Crypto.gcm(true, key, iv, p, 0, p.length);
        JSONObject o = new JSONObject();
        o.put("e", eph[1]);
        o.put("i", Crypto.b64(iv));
        o.put("c", Crypto.b64(ct));
        return o.toString();
    }

    public static String open(String privB64, String sealed) {
        try {
            JSONObject o = new JSONObject(sealed);
            byte[] key = agree(priv(privB64), pub(o.getString("e")));
            byte[] ct = Crypto.unb64(o.getString("c"));
            byte[] p = Crypto.gcm(false, key, Crypto.unb64(o.getString("i")), ct, 0, ct.length);
            return new String(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
