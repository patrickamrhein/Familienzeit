package de.familienzeit.common;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimaler Client für den kostenlosen Nachrichtendienst ntfy.sh. */
public final class Ntfy {
    public static final String SERVER = "https://ntfy.sh/";

    public static final class Msg {
        public final String id;
        public final long time;
        public final String body;

        Msg(String id, long time, String body) {
            this.id = id;
            this.time = time;
            this.body = body;
        }
    }

    private Ntfy() {}

    public static void publish(String topic, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(SERVER + topic).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        c.setRequestProperty("X-Cache", "yes");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code / 100 != 2) throw new Exception("ntfy publish HTTP " + code);
    }

    /** Holt alle zwischengespeicherten Nachrichten seit {@code since} (Nachrichten-ID oder "all"). */
    public static List<Msg> poll(String topic, String since) throws Exception {
        String s = (since == null || since.isEmpty()) ? "all" : since;
        URL url = new URL(SERVER + topic + "/json?poll=1&since=" + URLEncoder.encode(s, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        List<Msg> out = new ArrayList<>();
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("ntfy poll HTTP " + code);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    JSONObject o = new JSONObject(line);
                    if (!"message".equals(o.optString("event"))) continue;
                    String body = o.optString("message", "");
                    JSONObject att = o.optJSONObject("attachment");
                    if (att != null && att.has("url")) {
                        try {
                            body = new String(get(att.getString("url")), StandardCharsets.UTF_8);
                        } catch (Exception ignored) {
                            continue;
                        }
                    }
                    out.add(new Msg(o.optString("id"), o.optLong("time") * 1000L, body));
                }
            }
        } finally {
            c.disconnect();
        }
        return out;
    }

    public static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("HTTP " + code + " für " + url);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                return bos.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
