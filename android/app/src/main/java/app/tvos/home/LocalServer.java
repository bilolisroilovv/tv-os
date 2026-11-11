package app.tvos.home;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves https://tvos.local/ to the WebView:
 *   /<file>          the launcher (APK assets, i.e. web/ of the repository)
 *   /appart/<file>   app banners and icons that {@link Bridge} drew into the cache
 * Apple's Aerial films are not proxied: the WebView plays them straight from Apple's server.
 * Runs on WebView's network threads.
 */
final class LocalServer {
    private final AssetManager assets;
    private final File appart;

    LocalServer(Context c) {
        assets = c.getAssets();
        appart = new File(c.getCacheDir(), "appart");
    }

    WebResourceResponse handle(WebResourceRequest r) {
        Uri u = r.getUrl();
        if (!MainActivity.HOST.equals(u.getHost())) return null;
        String path = u.getPath();
        if (path == null || path.equals("/")) path = "/index.html";
        boolean head = "HEAD".equalsIgnoreCase(r.getMethod());
        try {
            if (path.startsWith("/appart/")) {
                File f = new File(appart, new File(path.substring(8)).getName());
                return f.isFile() ? ok("image/png", new FileInputStream(f), head) : missing();
            }
            String name = path.substring(1);
            if (name.contains("..")) return missing();
            return ok(mime(name), assets.open(name), head);
        } catch (IOException e) {
            Log.w("tvos", "cannot serve " + path + ": " + e);
            return missing();
        }
    }

    private static WebResourceResponse ok(String mime, InputStream in, boolean head) throws IOException {
        if (head) {
            in.close();
            in = new ByteArrayInputStream(new byte[0]);
        }
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-cache");
        String charset = mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json") ? "utf-8" : null;
        return new WebResourceResponse(mime, charset, 200, "OK", h, in);
    }

    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        return "application/octet-stream";
    }
}
