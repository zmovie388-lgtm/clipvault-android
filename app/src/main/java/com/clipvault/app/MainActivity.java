package com.clipvault.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static final String API = "https://clipvault-beta.vercel.app";
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean pageReady = false;
    private String pendingShare = null;
    private volatile HttpURLConnection activeConn = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0D10);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " ClipVaultApp/" + BuildConfigHelper.version(this));

        web.addJavascriptInterface(new Bridge(), "ClipVaultApp");
        web.setWebChromeClient(new WebChromeClient());
        WebViewClient base = new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("appassets.local".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingShare != null) { deliverShare(pendingShare); pendingShare = null; }
            }
        };
        // Serve bundled assets from a real https origin so fetch()/CORS behave like the website
        web.setWebViewClient(new AssetClient(this, base));
        web.loadUrl("https://appassets.local/www/index.html");

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (!m.find()) return;
        String url = m.group();
        if (pageReady) deliverShare(url); else pendingShare = url;
    }

    private void deliverShare(String url) {
        js("window.cvNative && window.cvNative.shared(" + JSONObject.quote(url) + ")");
    }

    private void js(String code) {
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    /* ------------------------------------------------------------------ */

    class Bridge {
        @JavascriptInterface
        public String apiBase() { return API; }

        @JavascriptInterface
        public String clipboard() {
            final String[] out = {""};
            final Object lock = new Object();
            ui.post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData cd = cm.getPrimaryClip();
                    if (cd != null && cd.getItemCount() > 0) {
                        CharSequence t = cd.getItemAt(0).coerceToText(MainActivity.this);
                        out[0] = t == null ? "" : t.toString();
                    }
                } catch (Exception ignored) { }
                synchronized (lock) { lock.notifyAll(); }
            });
            synchronized (lock) { try { lock.wait(1500); } catch (InterruptedException ignored) { } }
            return out[0];
        }

        @JavascriptInterface
        public void toast(String msg) { ui.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show()); }

        @JavascriptInterface
        public void cancel() {
            HttpURLConnection c = activeConn;
            if (c != null) c.disconnect();
        }

        /** body = same JSON the website POSTs to /api/fetch. Streams straight into Downloads/ClipVault. */
        @JavascriptInterface
        public void download(String body) {
            if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
                js("window.cvNative.error(" + JSONObject.quote("Allow storage permission, then tap download again.") + ")");
                return;
            }
            new Thread(() -> runDownload(body)).start();
        }

        @JavascriptInterface
        public void open(String uri, String mime) {
            ui.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse(uri), mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Open with"));
                } catch (Exception e) { toast("No app found to open this file"); }
            });
        }

        @JavascriptInterface
        public void share(String uri, String mime) {
            ui.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(mime);
                    i.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Share video"));
                } catch (Exception e) { toast("Couldn't share this file"); }
            });
        }
    }

    private void runDownload(String body) {
        HttpURLConnection c = null;
        Uri target = null;
        try {
            c = (HttpURLConnection) new URL(API + "/api/fetch").openConnection();
            activeConn = c;
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(310000);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }

            int code = c.getResponseCode();
            if (code != 200) {
                String msg = "Server error (" + code + ")";
                try (InputStream es = c.getErrorStream()) {
                    if (es != null) {
                        String raw = new String(readAll(es), "UTF-8");
                        msg = new JSONObject(raw).optString("detail", msg);
                    }
                } catch (Exception ignored) { }
                js("window.cvNative.error(" + JSONObject.quote(msg) + ")");
                return;
            }
            String name = c.getHeaderField("X-Filename");
            name = name == null ? "clipvault-" + System.currentTimeMillis() + ".mp4" : URLDecoder.decode(name, "UTF-8");
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String mime = c.getContentType() == null ? "video/mp4" : c.getContentType().split(";")[0];
            long total = c.getContentLengthLong();
            js("window.cvNative.receiving(" + total + ")");

            OutputStream out;
            String shareUri;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                boolean audio = mime.startsWith("audio");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, (audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/ClipVault");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri coll = audio ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                 : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                target = cr.insert(coll, v);
                if (target == null) throw new Exception("Couldn't create file in storage");
                out = cr.openOutputStream(target);
                shareUri = target.toString();
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ClipVault");
                dir.mkdirs();
                File f = new File(dir, name);
                out = new FileOutputStream(f);
                shareUri = FileProvider.getUriForFile(this, getPackageName() + ".files", f).toString();
                target = Uri.fromFile(f);
            }

            long got = 0, lastTick = 0, t0 = System.currentTimeMillis();
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = c.getInputStream(); OutputStream o = out) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    got += n;
                    long now = System.currentTimeMillis();
                    if (now - lastTick > 250) {
                        lastTick = now;
                        double speed = got / Math.max(0.2, (now - t0) / 1000.0);
                        js("window.cvNative.progress(" + got + "," + total + "," + (long) speed + ")");
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(target, done, null, null);
            } else {
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, target));
            }
            JSONObject r = new JSONObject();
            r.put("filename", name);
            r.put("size", got);
            r.put("mime", mime);
            r.put("uri", shareUri);
            r.put("folder", mime.startsWith("audio") ? "Music/ClipVault" : (Build.VERSION.SDK_INT >= 29 ? "Movies/ClipVault" : "Download/ClipVault"));
            js("window.cvNative.done(" + r + ")");
        } catch (Exception e) {
            if (target != null && Build.VERSION.SDK_INT >= 29) {
                try { getContentResolver().delete(target, null, null); } catch (Exception ignored) { }
            }
            String m = e.getMessage() == null ? "Download failed" : e.getMessage();
            js("window.cvNative.error(" + JSONObject.quote("Download failed: " + m) + ")");
        } finally {
            activeConn = null;
            if (c != null) c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }
}
