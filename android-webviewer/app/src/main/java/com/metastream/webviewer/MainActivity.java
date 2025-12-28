package com.metastream.webviewer;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String ASSET_ROOT = "file:///android_asset/";
    private static final String START_URL  = ASSET_ROOT + "index.html";

    private WebView wv;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Prevent Activity-level state restore from influencing WebView
        savedInstanceState = null;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wv = findViewById(R.id.webview);

        // Critical: prevent WebView from saving/restoring its own navigation state
        wv.setSaveEnabled(false);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        wv.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String url = u.toString();

                // Root-relative links like "/tabs/x.html" become "file:///tabs/x.html" in file://
                if (url.startsWith("file:///tabs/")) {
                    String rewritten = ASSET_ROOT + "tabs/" + url.substring("file:///tabs/".length());
                    view.loadUrl(rewritten);
                    return true;
                }

                // Some devices produce "file:/tabs/..."
                if (url.startsWith("file:/tabs/")) {
                    String rewritten = ASSET_ROOT + "tabs/" + url.substring("file:/tabs/".length());
                    view.loadUrl(rewritten);
                    return true;
                }

                return false;
            }

            // Extra safety for older/alternate WebView behaviors
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;

                if (url.startsWith("file:///tabs/")) {
                    String rewritten = ASSET_ROOT + "tabs/" + url.substring("file:///tabs/".length());
                    view.loadUrl(rewritten);
                    return true;
                }

                if (url.startsWith("file:/tabs/")) {
                    String rewritten = ASSET_ROOT + "tabs/" + url.substring("file:/tabs/".length());
                    view.loadUrl(rewritten);
                    return true;
                }

                return false;
            }
        });

        // Load start page
        wv.loadUrl(START_URL);

        // IMPORTANT: Some WebViews restore a previous page AFTER initial load.
        // Force index.html again on the next UI loop tick and clear history so it sticks.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (wv == null) return;
            wv.clearHistory();
            if (!START_URL.equals(wv.getUrl())) {
                wv.loadUrl(START_URL);
            }
        });
    }
}
