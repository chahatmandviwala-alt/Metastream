package com.metastream.webviewer;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String ASSET_ROOT = "file:///android_asset/";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Disable state restoration so we always start fresh
        savedInstanceState = null;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        WebView wv = findViewById(R.id.webview);

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

                // Rewrite root-relative links like "/tabs/xyz.html"
                // which become "file:///tabs/xyz.html" in file:// context
                if (url.startsWith("file:///tabs/")) {
                    String rewritten =
                        ASSET_ROOT + "tabs/" + url.substring("file:///tabs/".length());
                    view.loadUrl(rewritten);
                    return true;
                }

                if (url.startsWith("file:/tabs/")) {
                    String rewritten =
                        ASSET_ROOT + "tabs/" + url.substring("file:/tabs/".length());
                    view.loadUrl(rewritten);
                    return true;
                }

                return false;
            }
        });

        // Always start at index.html
        wv.loadUrl(ASSET_ROOT + "index.html");
    }
}
