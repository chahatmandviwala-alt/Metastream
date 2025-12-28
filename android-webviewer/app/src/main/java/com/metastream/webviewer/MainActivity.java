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

                // If your HTML uses root-relative links like "/tabs/xyz.html",
                // they become "file:///tabs/xyz.html" in file:// context.
                // Rewrite them to Android assets.
                if (url.startsWith("file:///tabs/")) {
                    String rewritten = ASSET_ROOT + "tabs/" + url.substring("file:///tabs/".length());
                    view.loadUrl(rewritten);
                    return true;
                }

                // Also handle "file:/tabs/..." variants just in case
                if (url.startsWith("file:/tabs/")) {
                    String rewritten = ASSET_ROOT + "tabs/" + url.substring("file:/tabs/".length());
                    view.loadUrl(rewritten);
                    return true;
                }

                return false; // let WebView handle everything else
            }
        });

        wv.loadUrl(ASSET_ROOT + "index.html");
    }
}
