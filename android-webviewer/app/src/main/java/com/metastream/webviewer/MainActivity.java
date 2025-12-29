package com.metastream.webviewer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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

    // ---- File chooser support ----
    private ValueCallback<Uri[]> filePathCallback;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onFileChooserResult);

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null); // disable state restore completely
        setContentView(R.layout.activity_main);

        // ---- start embedded offline HTTP server ----
        try {
            httpServer = new AssetHttpServer(this);
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.w(TAG, "Local server started: http://127.0.0.1:" + PORT + "/");
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

        // Recommended for modern sites (safe on localhost)
        s.setJavaScriptCanOpenWindowsAutomatically(true);

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

            // ---- Camera / media permission for getUserMedia() ----
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

            // ---- File upload support (<input type="file">) ----
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                // If there is an existing callback, cancel it to avoid leaks
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                    MainActivity.this.filePathCallback = null;
                }

                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception e) {
                    // Fallback if WebView can't create its own intent
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }

                // Ensure we can open something
                try {
                    fileChooserLauncher.launch(Intent.createChooser(intent, "Select file"));
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "File chooser launch failed", e);
                    if (MainActivity.this.filePathCallback != null) {
                        MainActivity.this.filePathCallback.onReceiveValue(null);
                        MainActivity.this.filePathCallback = null;
                    }
                    return false;
                }
            }
        });

        // ---- ALWAYS load via localhost so /api/* works offline ----
        webView.loadUrl("http://127.0.0.1:" + PORT + "/");
    }

    private void onFileChooserResult(ActivityResult result) {
        if (filePathCallback == null) return;

        Uri[] results = null;

        try {
            if (result.getResultCode() == RESULT_OK) {
                Intent data = result.getData();
                if (data != null) {
                    // Multiple selection
                    ClipData clipData = data.getClipData();
                    if (clipData != null && clipData.getItemCount() > 0) {
                        results = new Uri[clipData.getItemCount()];
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            results[i] = clipData.getItemAt(i).getUri();
                        }
                    } else {
                        // Single selection
                        Uri uri = data.getData();
                        if (uri != null) {
                            results = new Uri[]{uri};
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling file chooser result", e);
        }

        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
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

        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
    }
}
