package com.metastream.webviewer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

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

    // ---- Download support ----
    private String pendingDownloadUrl;
    private String pendingDownloadMime;
    private String pendingDownloadFilename;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onFileChooserResult);

    private final ActivityResultLauncher<Intent> createDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onCreateDocumentResult);

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
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                    MainActivity.this.filePathCallback = null;
                }

                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }

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

        // ---- Download support ----
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimetype,
                    long contentLength
            ) {
                // WebView can trigger downloads for blob:, data:, etc. We only support http(s) URLs here.
                if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    Log.e(TAG, "Download unsupported URL scheme: " + url);
                    Toast.makeText(MainActivity.this, "Download not supported for this link type.", Toast.LENGTH_LONG).show();
                    return;
                }

                pendingDownloadUrl = url;
                pendingDownloadMime = (mimetype == null || mimetype.trim().isEmpty())
                        ? "application/octet-stream"
                        : mimetype;

                pendingDownloadFilename = guessFilename(url, contentDisposition, pendingDownloadMime);

                // Ask user where to save (no storage permission needed)
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(pendingDownloadMime);
                i.putExtra(Intent.EXTRA_TITLE, pendingDownloadFilename);

                try {
                    createDocumentLauncher.launch(i);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to launch save dialog", e);
                    Toast.makeText(MainActivity.this, "Could not open save dialog.", Toast.LENGTH_LONG).show();
                    clearPendingDownload();
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
                    ClipData clipData = data.getClipData();
                    if (clipData != null && clipData.getItemCount() > 0) {
                        results = new Uri[clipData.getItemCount()];
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            results[i] = clipData.getItemAt(i).getUri();
                        }
                    } else {
                        Uri uri = data.getData();
                        if (uri != null) results = new Uri[]{uri};
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling file chooser result", e);
        }

        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    private void onCreateDocumentResult(ActivityResult result) {
        if (pendingDownloadUrl == null) return;

        if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            Toast.makeText(this, "Download cancelled.", Toast.LENGTH_SHORT).show();
            clearPendingDownload();
            return;
        }

        Uri destUri = result.getData().getData();

        // Stream download in a background thread (avoid blocking UI)
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            OutputStream out = null;

            try {
                URL u = new URL(pendingDownloadUrl);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new Exception("HTTP " + code);
                }

                in = conn.getInputStream();

                ContentResolver cr = getContentResolver();
                out = cr.openOutputStream(destUri, "w");
                if (out == null) throw new Exception("Could not open output stream");

                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();

                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Downloaded: " + pendingDownloadFilename, Toast.LENGTH_LONG).show()
                );

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                try { if (out != null) out.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
                clearPendingDownload();
            }
        }).start();
    }

    private void clearPendingDownload() {
        pendingDownloadUrl = null;
        pendingDownloadMime = null;
        pendingDownloadFilename = null;
    }

    private static String guessFilename(String url, String contentDisposition, String mime) {
        // Prefer filename from Content-Disposition if present
        if (contentDisposition != null) {
            String cd = contentDisposition;
            String lower = cd.toLowerCase();
            int idx = lower.indexOf("filename=");
            if (idx >= 0) {
                String v = cd.substring(idx + "filename=".length()).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
                v = v.replaceAll("[\\\\/:*?\"<>|]+", "_");
                if (!v.isEmpty()) return v;
            }
        }

        // Otherwise use the URL path
        try {
            Uri u = Uri.parse(url);
            String last = u.getLastPathSegment();
            if (last != null && !last.trim().isEmpty()) {
                last = last.replaceAll("[\\\\/:*?\"<>|]+", "_");
                return last;
            }
        } catch (Exception ignored) {}

        // Fallback generic name
        String ext = "";
        if (mime != null) {
            if (mime.contains("json")) ext = ".json";
            else if (mime.contains("png")) ext = ".png";
            else if (mime.contains("pdf")) ext = ".pdf";
            else if (mime.contains("text")) ext = ".txt";
        }
        return "download" + ext;
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

        clearPendingDownload();
    }
}
