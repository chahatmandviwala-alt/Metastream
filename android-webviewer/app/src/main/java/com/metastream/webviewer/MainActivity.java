package com.metastream.webviewer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
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
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Metastream";
    private static final int PORT = 3000;
    private static final int REQ_CAMERA = 1001;

    private ImeBlockWebView webView;
    private android.view.View focusSink;
    private volatile boolean vkMode = false;
    private AssetHttpServer httpServer;
    private PermissionRequest pendingPermissionRequest;

    // ---- File chooser support ----
    private ValueCallback<Uri[]> filePathCallback;

    // ---- Download support (http/https) ----
    private String pendingDownloadUrl;
    private String pendingDownloadMime;
    private String pendingDownloadFilename;

    // ---- Download support (blob/data via JS bridge) ----
    private String pendingBridgeFilename;
    private String pendingBridgeMime;
    private byte[] pendingBridgeBytes;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onFileChooserResult);

    private final ActivityResultLauncher<Intent> createDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onCreateDocumentResult);

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null); // disable state restore completely
        setContentView(R.layout.activity_main);

        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        // ---- start embedded offline HTTP server ----
        try {
            httpServer = new AssetHttpServer(this);
            httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.w(TAG, "Local server started: http://127.0.0.1:" + PORT + "/");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start local server", e);
        }

        webView = findViewById(R.id.webview);
        

        // A non-text focus sink used to keep Android focus away from WebView while the in-page VK is active.
        // This prevents WebView from negotiating an InputConnection with the IME.
        android.view.ViewGroup root = findViewById(android.R.id.content);
        focusSink = new android.view.View(this);
        focusSink.setFocusable(true);
        focusSink.setFocusableInTouchMode(true);
        focusSink.setAlpha(0f);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(1, 1);
        root.addView(focusSink, lp);
webView.setSaveEnabled(false);

        // Stable focus behavior for in-page keyboards
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);
        webView.setLongClickable(true);
        webView.requestFocus();

        // JS bridges
        webView.addJavascriptInterface(new AndroidDownloadBridge(), "AndroidDownloadBridge");
        webView.addJavascriptInterface(new AndroidImeBridge(), "AndroidImeBridge");

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        WebView.setWebContentsDebuggingEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectFocusKeeper();
                injectVkImeSuppressor();   // vkToggleBtn click => suppress/restore system IME via inputmode="none"
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                Log.e("WEBVIEW_CONSOLE",
                        msg.message() + " (" + msg.sourceId() + ":" + msg.lineNumber() + ")");
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

        // Touch handling:
        // - When VK is OFF: keep WebView focused normally.
        // - When VK is ON: keep Android focus on a non-text focus sink, while still letting the WebView receive touch events.
        webView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (vkMode) {
                    if (focusSink != null) {
                        focusSink.requestFocus();
                    }
                    hideImeNow();
                    return false;
                }
                if (!v.hasFocus()) {
                    v.requestFocus();
                }
            }
            return false;
        });
}
            return false;
        });

        // Downloads
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimetype,
                    long contentLength
            ) {
                if (url == null) {
                    Toast.makeText(MainActivity.this, "Download failed: empty URL", Toast.LENGTH_LONG).show();
                    return;
                }

                String mt = (mimetype == null || mimetype.trim().isEmpty())
                        ? "application/octet-stream"
                        : mimetype;

                String filename = guessFilename(url, contentDisposition, mt);

                if (url.startsWith("http://") || url.startsWith("https://")) {
                    pendingDownloadUrl = url;
                    pendingDownloadMime = mt;
                    pendingDownloadFilename = filename;
                    launchCreateDocument(pendingDownloadFilename, pendingDownloadMime);
                    return;
                }

                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    pendingBridgeFilename = filename;
                    pendingBridgeMime = mt;
                    pendingBridgeBytes = null;
                    startBridgeDownload(url, filename, mt);
                    return;
                }

                Toast.makeText(MainActivity.this, "Download not supported for this link type.", Toast.LENGTH_LONG).show();
            }
        });

        // Load offline UI
        webView.loadUrl("http://127.0.0.1:" + PORT + "/");
    }

    /**
     * Focus keeper so clicking your in-page keyboard doesn't blur the input.
     */
    private void injectFocusKeeper() {
        String js =
                "(function(){\n" +
                "  if (window.__ms_focusKeeperInstalled) return;\n" +
                "  window.__ms_focusKeeperInstalled = true;\n" +
                "  let last = null;\n" +
                "  function isEditable(el){\n" +
                "    if (!el) return false;\n" +
                "    const t = (el.tagName||'').toLowerCase();\n" +
                "    return (t==='input' || t==='textarea' || el.isContentEditable);\n" +
                "  }\n" +
                "  document.addEventListener('focusin', function(e){\n" +
                "    if (isEditable(e.target)) last = e.target;\n" +
                "  }, true);\n" +
                "  function refocus(){\n" +
                "    try {\n" +
                "      if (!last) return;\n" +
                "      if (!document.contains(last)) return;\n" +
                "      const a = document.activeElement;\n" +
                "      if (a === last) return;\n" +
                "      if (a && isEditable(a)) return;\n" +
                "      last.focus({preventScroll:true});\n" +
                "    } catch (_) {}\n" +
                "  }\n" +
                "  document.addEventListener('pointerdown', function(){ setTimeout(refocus, 0); }, true);\n" +
                "  document.addEventListener('mousedown',  function(){ setTimeout(refocus, 0); }, true);\n" +
                "  document.addEventListener('touchstart', function(){ setTimeout(refocus, 0); }, true);\n" +
                "  document.addEventListener('click',      function(){ setTimeout(refocus, 0); }, true);\n" +
                "})();";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    
    /**
     * Robust VK/IME suppressor.
     *
     * - Treats each click on #vkToggleBtn as a toggle.
     * - When VK is ON: sets inputmode="none" (and disables autocomplete/correct/capitalize/spellcheck),
     *   with enforcement on focus/input and on DOM mutations (for dynamically created inputs).
     * - Restores original attributes when VK is OFF.
     * - Calls AndroidImeBridge.vkOn()/vkOff() for native-side best-effort hide/block.
     *
     * No web source files are modified; this is injection-only.
     */
    private void injectVkImeSuppressor() {
        String js =
                "(function(){\n" +
                "  if (window.__ms_vkImeSuppressorInstalled) return;\n" +
                "  window.__ms_vkImeSuppressorInstalled = true;\n" +
                "  const SEL = 'input, textarea, [contenteditable=""], [contenteditable="true"], [contenteditable="plaintext-only"]';\n" +
                "  const saved = new WeakMap();\n" +
                "  function isEditable(el){\n" +
                "    if (!el) return false;\n" +
                "    const t = (el.tagName||'').toLowerCase();\n" +
                "    return (t==='input' || t==='textarea' || el.isContentEditable);\n" +
                "  }\n" +
                "  function snapshot(el){\n" +
                "    if (!el || saved.has(el)) return;\n" +
                "    saved.set(el, {\n" +
                "      inputmode: el.getAttribute('inputmode'),\n" +
                "      autocomplete: el.getAttribute('autocomplete'),\n" +
                "      autocorrect: el.getAttribute('autocorrect'),\n" +
                "      autocapitalize: el.getAttribute('autocapitalize'),\n" +
                "      spellcheck: el.getAttribute('spellcheck')\n" +
                "    });\n" +
                "  }\n" +
                "  function applyTo(el){\n" +
                "    if (!isEditable(el)) return;\n" +
                "    snapshot(el);\n" +
                "    el.setAttribute('inputmode','none');\n" +
                "    el.setAttribute('autocomplete','off');\n" +
                "    el.setAttribute('autocorrect','off');\n" +
                "    el.setAttribute('autocapitalize','off');\n" +
                "    el.setAttribute('spellcheck','false');\n" +
                "  }\n" +
                "  function restore(el){\n" +
                "    if (!isEditable(el)) return;\n" +
                "    const s = saved.get(el);\n" +
                "    if (!s) { el.removeAttribute('inputmode'); return; }\n" +
                "    if (s.inputmode == null) el.removeAttribute('inputmode'); else el.setAttribute('inputmode', s.inputmode);\n" +
                "    if (s.autocomplete == null) el.removeAttribute('autocomplete'); else el.setAttribute('autocomplete', s.autocomplete);\n" +
                "    if (s.autocorrect == null) el.removeAttribute('autocorrect'); else el.setAttribute('autocorrect', s.autocorrect);\n" +
                "    if (s.autocapitalize == null) el.removeAttribute('autocapitalize'); else el.setAttribute('autocapitalize', s.autocapitalize);\n" +
                "    if (s.spellcheck == null) el.removeAttribute('spellcheck'); else el.setAttribute('spellcheck', s.spellcheck);\n" +
                "  }\n" +
                "  function applyAll(){\n" +
                "    document.querySelectorAll(SEL).forEach(applyTo);\n" +
                "    const a = document.activeElement;\n" +
                "    if (isEditable(a)) { try { a.blur(); a.focus({preventScroll:true}); } catch(e) { try { a.blur(); a.focus(); } catch(_){} } }\n" +
                "  }\n" +
                "  function restoreAll(){\n" +
                "    document.querySelectorAll(SEL).forEach(restore);\n" +
                "    const a = document.activeElement;\n" +
                "    if (isEditable(a)) { try { a.blur(); a.focus({preventScroll:true}); } catch(e) { try { a.blur(); a.focus(); } catch(_){} } }\n" +
                "  }\n" +
                "  function setMode(on){\n" +
                "    window.__ms_vk_mode = !!on;\n" +
                "    if (window.__ms_vk_mode) {\n" +
                "      applyAll();\n" +
                "      try { AndroidImeBridge.vkOn(); } catch(e) {}\n" +
                "    } else {\n" +
                "      restoreAll();\n" +
                "      try { AndroidImeBridge.vkOff(); } catch(e) {}\n" +
                "    }\n" +
                "  }\n" +
                "  // Hook button: treat each click as a toggle (does not depend on aria/class state).\n" +
                "  const btn = document.getElementById('vkToggleBtn');\n" +
                "  if (btn) {\n" +
                "    btn.addEventListener('click', function(){ setMode(!window.__ms_vk_mode); }, true);\n" +
                "  }\n" +
                "  // Enforcement: some IMEs re-open on programmatic value changes while an input is focused.\n" +
                "  function enforce(ev){\n" +
                "    if (!window.__ms_vk_mode) return;\n" +
                "    const t = ev && ev.target ? ev.target : document.activeElement;\n" +
                "    if (isEditable(t)) applyTo(t);\n" +
                "    try { AndroidImeBridge.vkOn(); } catch(e) {}\n" +
                "  }\n" +
                "  document.addEventListener('focusin', enforce, true);\n" +
                "  document.addEventListener('beforeinput', enforce, true);\n" +
                "  document.addEventListener('input', enforce, true);\n" +
                "  // If inputs are created dynamically, ensure they are covered.\n" +
                "  try {\n" +
                "    const mo = new MutationObserver(function(){ if (window.__ms_vk_mode) applyAll(); });\n" +
                "    mo.observe(document.documentElement || document.body, {subtree:true, childList:true, attributes:false});\n" +
                "  } catch(e) {}\n" +
                "  // Initialize stored flag if not present.\n" +
                "  window.__ms_vk_mode = !!window.__ms_vk_mode;\n" +
                "  if (window.__ms_vk_mode) setMode(true);\n" +
                "})();";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    /**
     * IME control via reflection ONLY (no compile-time dependency).
     */
    private void setShowSoftInputOnFocusCompat(boolean enabled) {
        try {
            Method m = WebView.class.getMethod("setShowSoftInputOnFocus", boolean.class);
            m.invoke(webView, enabled);
        } catch (Throwable ignored) {
            // Some devices/APIs may not support it; we still hide IME proactively.
        }
    }

    private void restartInputSafe() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && webView != null) {
                imm.restartInput(webView);
            }
        } catch (Throwable ignored) {
        }
    }

private void hideImeNow() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && webView != null) {
                imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
    }

private final class AndroidImeBridge {
    @JavascriptInterface
    public void vkOn() {
        runOnUiThread(() -> {
            // Best-effort: prevent OSK from showing for focused fields while VK is active.
            setShowSoftInputOnFocusCompat(false);


            vkMode = true;
            // Prevent WebView from becoming the focused text editor while VK is active.
            webView.setFocusable(false);
            webView.setFocusableInTouchMode(false);
            webView.clearFocus();
            if (focusSink != null) {
                focusSink.requestFocus();
            }
            // Block IME at the WebView InputConnection layer (ImeBlockWebView).
            webView.setImeBlocked(true);

            // Close any already-open keyboard immediately.
            hideImeNow();

            // IMPORTANT: do NOT call restartInput() here; it can trigger alternating IME state caching
            // (the odd/even behavior you observed). The web-layer inputmode="none" is the primary suppressor.
        });
    }

    @JavascriptInterface
    public void vkOff() {
        runOnUiThread(() -> {
            vkMode = false;
            // Restore normal focus behavior.
            webView.setFocusable(true);
            webView.setFocusableInTouchMode(true);
            // Restore normal OSK behavior.
            webView.setImeBlocked(false);
            setShowSoftInputOnFocusCompat(true);

            // Ensure the IME can attach cleanly on next focus.
            restartImeNow();
        });
    }
}

    private void restartImeNow() {
    try {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && webView != null) {
            imm.restartInput(webView);
        }
    } catch (Throwable ignored) {}
}

    private void launchCreateDocument(String filename, String mime) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, filename);
        try {
            createDocumentLauncher.launch(i);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch save dialog", e);
            Toast.makeText(MainActivity.this, "Could not open save dialog.", Toast.LENGTH_LONG).show();
            clearPendingDownload();
            clearPendingBridgeDownload();
        }
    }

    private void startBridgeDownload(String url, String filename, String mime) {
        String js =
                "(function(){\n" +
                "  try {\n" +
                "    const u = " + jsString(url) + ";\n" +
                "    fetch(u).then(r => r.arrayBuffer()).then(buf => {\n" +
                "      const bytes = new Uint8Array(buf);\n" +
                "      let bin = '';\n" +
                "      const chunk = 0x8000;\n" +
                "      for (let i = 0; i < bytes.length; i += chunk) {\n" +
                "        bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));\n" +
                "      }\n" +
                "      const b64 = btoa(bin);\n" +
                "      AndroidDownloadBridge.onBlobBase64(" + jsString(filename) + "," + jsString(mime) + ", b64);\n" +
                "    }).catch(e => AndroidDownloadBridge.onBlobError(String(e)));\n" +
                "  } catch (e) {\n" +
                "    AndroidDownloadBridge.onBlobError(String(e));\n" +
                "  }\n" +
                "})();";

        runOnUiThread(() -> webView.evaluateJavascript(js, null));
        Toast.makeText(this, "Preparing download…", Toast.LENGTH_SHORT).show();
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
        if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) {
            Toast.makeText(this, "Download cancelled.", Toast.LENGTH_SHORT).show();
            clearPendingDownload();
            clearPendingBridgeDownload();
            return;
        }

        Uri destUri = result.getData().getData();

        if (pendingBridgeBytes != null) {
            writeBytesToUriAsync(destUri, pendingBridgeBytes, pendingBridgeFilename);
            clearPendingBridgeDownload();
            return;
        }

        if (pendingDownloadUrl != null) {
            streamHttpToUriAsync(destUri, pendingDownloadUrl, pendingDownloadFilename);
            clearPendingDownload();
            return;
        }

        Toast.makeText(this, "Nothing to download.", Toast.LENGTH_SHORT).show();
        clearPendingDownload();
        clearPendingBridgeDownload();
    }

    private void streamHttpToUriAsync(Uri destUri, String url, String filename) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            OutputStream out = null;

            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

                in = conn.getInputStream();

                ContentResolver cr = getContentResolver();
                out = cr.openOutputStream(destUri, "w");
                if (out == null) throw new Exception("Could not open output stream");

                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                out.flush();

                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Downloaded: " + filename, Toast.LENGTH_LONG).show()
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
            }
        }).start();
    }

    private void writeBytesToUriAsync(Uri destUri, byte[] bytes, String filename) {
        new Thread(() -> {
            OutputStream out = null;
            try {
                out = getContentResolver().openOutputStream(destUri, "w");
                if (out == null) throw new Exception("Could not open output stream");
                out.write(bytes);
                out.flush();
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Downloaded: " + filename, Toast.LENGTH_LONG).show()
                );
            } catch (Exception e) {
                Log.e(TAG, "Write download failed", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            } finally {
                try { if (out != null) out.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    private void clearPendingDownload() {
        pendingDownloadUrl = null;
        pendingDownloadMime = null;
        pendingDownloadFilename = null;
    }

    private void clearPendingBridgeDownload() {
        pendingBridgeFilename = null;
        pendingBridgeMime = null;
        pendingBridgeBytes = null;
    }

    private final class AndroidDownloadBridge {
        @JavascriptInterface
        public void onBlobBase64(String filename, String mime, String base64) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                pendingBridgeFilename = (filename == null || filename.isEmpty()) ? "download" : filename;
                pendingBridgeMime = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
                pendingBridgeBytes = data;
                runOnUiThread(() -> launchCreateDocument(pendingBridgeFilename, pendingBridgeMime));
            } catch (Exception e) {
                Log.e(TAG, "Blob decode failed", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
                clearPendingBridgeDownload();
            }
        }

        @JavascriptInterface
        public void onBlobError(String message) {
            Log.e(TAG, "Blob download JS error: " + message);
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this, "Download failed: " + message, Toast.LENGTH_LONG).show()
            );
            clearPendingBridgeDownload();
        }
    }

    private static String guessFilename(String url, String contentDisposition, String mime) {
        if (contentDisposition != null) {
            String cd = contentDisposition;
            String lower = cd.toLowerCase();
            int idx = lower.indexOf("filename=");
            if (idx >= 0) {
                String v = cd.substring(idx + "filename=".length()).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                v = v.replaceAll("[\\\\/:*?\"<>|]+", "_");
                if (!v.isEmpty()) return v;
            }
        }

        try {
            Uri u = Uri.parse(url);
            String last = u.getLastPathSegment();
            if (last != null && !last.trim().isEmpty()) {
                last = last.replaceAll("[\\\\/:*?\"<>|]+", "_");
                return last;
            }
        } catch (Exception ignored) {}

        String ext = "";
        if (mime != null) {
            String m = mime.toLowerCase();
            if (m.contains("json")) ext = ".json";
            else if (m.contains("png")) ext = ".png";
            else if (m.contains("pdf")) ext = ".pdf";
            else if (m.contains("text")) ext = ".txt";
        }
        return "download" + ext;
    }

    private static String jsString(String s) {
        if (s == null) return "''";
        return "'" + s
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "'";
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
        clearPendingBridgeDownload();
    }
}
