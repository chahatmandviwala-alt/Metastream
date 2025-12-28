package com.metastream.webviewer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 3000;
    private static final int REQ_CAMERA = 1001;

    private WebView wv;
    private AssetHttpServer httpServer;

    // If a page requests camera before runtime permission is granted,
    // we hold the WebView permission request and resolve it after user action.
    private PermissionRequest pendingWebViewPermissionRequest = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Avoid Activity-level restore influencing WebView
        savedInstanceState = null;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Start embedded localhost server for offline mode
        try {
            httpServer = new AssetHttpServer(this);
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (Exception e) {
            e.printStackTrace();
        }

        wv = findViewById(R.id.webview);
        wv.setSaveEnabled(false);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        // Required for many WebView camera/file flows
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        // Keep navigation inside WebView
        wv.setWebViewClient(new WebViewClient());

        // Enable camera + file chooser hooks
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    // If the page requests video/audio capture, Android runtime permission may be needed
                    boolean needsCamera = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            needsCamera = true;
                        }
                    }

                    if (needsCamera) {
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                                != PackageManager.PERMISSION_GRANTED) {
                            pendingWebViewPermissionRequest = request;
                            ActivityCompat.requestPermissions(
                                    MainActivity.this,
                                    new String[]{Manifest.permission.CAMERA},
                                    REQ_CAMERA
                            );
                            return; // wait for user response
                        }
                    }

                    // Grant requested resources
                    request.grant(request.getResources());
                });
            }
        });

        // Load the app from localhost so /api/... works offline
        wv.loadUrl("http://127.0.0.1:" + PORT + "/");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_CAMERA) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (pendingWebViewPermissionRequest != null) {
                if (granted) {
                    pendingWebViewPermissionRequest.grant(pendingWebViewPermissionRequest.getResources());
                } else {
                    pendingWebViewPermissionRequest.deny();
                }
                pendingWebViewPermissionRequest = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Stop server
        if (httpServer != null) {
            try {
                httpServer.stop();
            } catch (Exception ignored) {}
            httpServer = null;
        }

        // Clean up WebView
        if (wv != null) {
            try {
                wv.destroy();
            } catch (Exception ignored) {}
            wv = null;
        }
    }
}
