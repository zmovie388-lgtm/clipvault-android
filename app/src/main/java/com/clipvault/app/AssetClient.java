package com.clipvault.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;

/** Serves app/src/main/assets/* at https://appassets.local/* and forwards other callbacks. */
public class AssetClient extends WebViewClient {
    private final Context ctx;
    private final WebViewClient inner;

    AssetClient(Context ctx, WebViewClient inner) {
        this.ctx = ctx;
        this.inner = inner;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri u = request.getUrl();
        if (!"appassets.local".equals(u.getHost())) return null;
        String path = u.getPath();
        if (path == null || path.equals("/")) path = "/www/index.html";
        try {
            InputStream in = ctx.getAssets().open(path.substring(1));
            return new WebResourceResponse(mime(path), "UTF-8", in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
        }
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return inner != null && inner.shouldOverrideUrlLoading(view, request);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        if (inner != null) inner.onPageStarted(view, url, favicon);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        if (inner != null) inner.onPageFinished(view, url);
    }
}
