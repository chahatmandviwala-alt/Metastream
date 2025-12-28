package com.metastream.webviewer;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String ASSET_ROOT = "file:///android_asset/";
    private static final String START_URL  = ASSET_ROOT + "index.html";

    private WebView wv;

    // For the first ~2 seconds, force START_URL and block auto-jumps into tabs
    private long launchUptimeMs;
    private boolean startupLock = true;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Avoid Activity-level restore influencing WebView
        savedInstanceState = null;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        launchUptimeMs = SystemClock.uptimeMillis();

        wv = findViewById(R.id.webview);
        wv.setSaveEnabled(false);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        wv.setWebViewClient(new WebViewClient() {

            private boolean isWithinStartupWindow() {
                return startupLock && (SystemClock.uptimeMillis() - launchUptimeMs) < 2000;
            }

            private boolean isTabUrl(String url) {
                if (url == null) return false;
                // These are the two forms you have seen
                return url.startsWith("file:///tabs/") ||
                       url.startsWith("file:/tabs/") ||
                       url.startsWith(ASSET_ROOT + "tabs/");
            }

            private String rewriteTabUrlToAssets(String url) {
                if (url.startsWith("file:///tabs/")) {
                    return ASSET_ROOT + "tabs/" + url.substring("file:///tabs/".length());
                }
                if (url.startsWith("file:/tabs/")) {
                    return ASSET_ROOT + "tabs/" + url.substring("file:/tabs/".length());
                }
                return url;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String url = (u == null) ? null : u.toString();

                // During startup: do NOT allow automatic navigation into tabs.
                if (isWithinStartupWindow() && isTabUrl(url)) {
                    // Force back to index.html
                    view.loadUrl(START_URL);
                    return true;
                }

                // Normal operation: rewrite /tabs links to assets so they load.
                if (isTabUrl(url) && (url.startsWith("file:/tabs/") || url.startsWith("file:///tabs/"))) {
                    view.loadUrl(rewriteTabUrlToAssets(url));
                    return true;
                }

                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // If something managed to load a tab during startup, immediately correct it.
                if (isWithinStartupWindow() && isTabUrl(url)) {
                    view.loadUrl(START_URL);
                    return;
                }

                // Once index.html has loaded, release the startup lock shortly after.
                if (START_URL.equals(url)) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> startupLock = false, 300);
                }
            }
        });

        wv.setWebChromeClient(new WebChromeClient());

        // Start clean and load index.html
        wv.clearHistory();
        wv.clearCache(true);
        wv.loadUrl(START_URL);
    }
}
