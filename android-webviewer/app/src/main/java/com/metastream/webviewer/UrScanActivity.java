package com.metastream.webviewer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;

import me.dm7.barcodescanner.zbar.ZBarScannerView;
import me.dm7.barcodescanner.zbar.Result;

public class UrScanActivity extends AppCompatActivity implements ZBarScannerView.ResultHandler {

    private ZBarScannerView scannerView;

    // De-dupe by total-index key so we don't flood the server
    private final HashSet<String> seenSeq = new HashSet<>();

    private String apiPartUrl;
    private String apiDecodedUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int port = getIntent().getIntExtra("LOCAL_PORT", 0);
        if (port == 0) port = 3000; // fallback
        apiPartUrl = "http://127.0.0.1:" + port + "/api/ur/part";
        apiDecodedUrl = "http://127.0.0.1:" + port + "/api/ur/decoded";
        scannerView = new ZBarScannerView(this);
        setContentView(scannerView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        scannerView.setResultHandler(this);
        scannerView.startCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        scannerView.stopCamera();
    }

    @Override
    public void handleResult(Result rawResult) {
        if (rawResult == null) {
            scannerView.resumeCameraPreview(this);
            return;
        }

        final String text = rawResult.getContents() == null ? "" : rawResult.getContents().trim();
        if (text.isEmpty()) {
            scannerView.resumeCameraPreview(this);
            return;
        }

        final String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("ur:")) {
            scannerView.resumeCameraPreview(this);
            return;
        }

        // De-dupe by "total-index" if present: ur:type/843-5/...
        String key = extractSeqKey(lower);
        if (key != null) {
            synchronized (seenSeq) {
                if (!seenSeq.add(key)) {
                    scannerView.resumeCameraPreview(this);
                    return;
                }
            }
        }

        new Thread(() -> {
            try {
                postPart(text);

                if (isDecodedAvailable()) {
                    runOnUiThread(() -> {
                        setResult(Activity.RESULT_OK, new Intent());
                        finish();
                    });
                    return;
                }
            } catch (Exception ignored) {
            }

            runOnUiThread(() -> scannerView.resumeCameraPreview(this));
        }).start();
    }

    private static String extractSeqKey(String lower) {
        // ur:type/299-5/...
        int firstSlash = lower.indexOf('/');
        if (firstSlash <= 0) return null;
        int secondSlash = lower.indexOf('/', firstSlash + 1);
        if (secondSlash <= firstSlash) return null;

        String seq = lower.substring(firstSlash + 1, secondSlash); // "299-5"
        return seq.contains("-") ? seq : null;
    }

    private static void postPart(String part) throws Exception {
        URL url = new URL(apiPartUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(1500);
        conn.setReadTimeout(2500);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);

        String body = "{\"part\":\"" + escapeJson(part) + "\"}";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        conn.getInputStream().close();
        conn.disconnect();
    }

    private static boolean isDecodedAvailable() {
        try {
            URL url = new URL(apiDecodedUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");

            byte[] buf = conn.getInputStream().readAllBytes();
            conn.disconnect();

            String s = new String(buf, StandardCharsets.UTF_8);
            return !s.contains("\"error\"");
        } catch (Exception e) {
            return false;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
