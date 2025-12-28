package com.metastream.webviewer;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        WebView wv = findViewById(R.id.webview);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        wv.setWebViewClient(new WebViewClient());

        // NOTE: 127.0.0.1 inside Android is the phone/emulator itself, not your PC.
        // This will show a blank/fail unless the app content is bundled as assets or you use a reachable host.
        wv.loadUrl("file:///android_asset/index.html");
    }
}
