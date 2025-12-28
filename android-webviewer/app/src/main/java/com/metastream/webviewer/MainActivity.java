package com.metastream.webviewer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
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

    private static final String TAG = "Metastream";
    private static final int PORT = 3000;
    private static final int REQ_CAMERA = 1001;

    private WebView webView;
    private AssetHttpServer httpServer;
    private PermissionRequest pendingPermissionRequest;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null); // disable state restore completely
        setContentView(R.layout.activity_main);

        // ---- start embedded offline HTTP server ----
        try {
            httpServer = new AssetHttpServer(this);
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.i(TAG, "Local server started on port " + PORT);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start local server", e);
        }

        webView = findViewById(R.id.webview);
        webView.setSaveEnabled(false);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        WebView.setWebContentsDebuggingEnabled(true);

        webView.setWebViewClient(new WebViewClient());

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                Log.e(
                        "WEBVIEW_CONSOLE",
                        msg.message() + " (" + msg.sourceId() + ":" + msg.lineNumber() + ")"
                );
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean needsCamera = false;

                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            needsCamera = true;
                        }
                    }

                    if (needsCamera &&
                            ContextCompat.checkSelfPermission(
                                    MainActivity.this,
                                    Manifest.permission.CAMERA
                            ) != PackageManager.PERMISSION_GRANTED) {

                        pendingPermissionRequest = request;
                        ActivityCompat.requestPermissions(
                                MainActivity.this,
                                new String[]{Manifest.permission.CAMERA},
                                REQ_CAMERA
                        );
                        return;
                    }

                    request.grant(request.getResources());
                });
            }
        });

        // ---- ALWAYS load via localhost so /api/* works offline ----
        webView.loadUrl("http://127.0.0.1:" + PORT + "/");
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_CAMERA && pendingPermissionRequest != null) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingPermissionRequest.grant(
                        pendingPermissionRequest.getResources()
                );
            } else {
                pendingPermissionRequest.deny();
            }
            pendingPermissionRequest = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }

        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
