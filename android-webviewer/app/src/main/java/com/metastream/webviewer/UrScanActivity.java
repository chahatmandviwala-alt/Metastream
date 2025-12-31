package com.metastream.webviewer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class UrScanActivity extends Activity {

    private DecoratedBarcodeView barcodeView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile long lastPostAtMs = 0;

    private static final String API_PART_URL = "http://127.0.0.1:3000/api/ur/part";
    private static final String API_DECODED_URL = "http://127.0.0.1:3000/api/ur/decoded";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ur_scan);

        barcodeView = findViewById(R.id.barcode_scanner);
        barcodeView.decodeContinuous(callback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        barcodeView.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        barcodeView.pause();
    }

    private final BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            if (result == null || result.getText() == null) return;

            final String text = result.getText().trim();
            if (text.isEmpty()) return;

            // We only care about UR frames
            final String lower = text.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("ur:")) return;

            // Throttle posting (prevents flooding if the same frame is repeatedly detected)
            long now = System.currentTimeMillis();
            if (now - lastPostAtMs < 80) return; // ~12.5 posts/sec max
            lastPostAtMs = now;

            // Post off the UI thread
            new Thread(() -> {
                try {
                    postPart(text);

                    // If server has assembled the UR, close scanner
                    if (isDecodedAvailable()) {
                        mainHandler.post(() -> {
                            setResult(Activity.RESULT_OK);
                            finish();
                        });
                    }
                } catch (Exception ignored) {
                    // silent: scanning should continue even if one post fails
                }
            }).start();
        }
    };

    private static void postPart(String part) throws Exception {
        URL url = new URL(API_PART_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(1500);
        conn.setReadTimeout(2000);
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
            URL url = new URL(API_DECODED_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");

            byte[] buf = conn.getInputStream().readAllBytes();
            conn.disconnect();

            String s = new String(buf, StandardCharsets.UTF_8);
            // Your /api/ur/decoded returns {"error":"..."} until ready. :contentReference[oaicite:1]{index=1}
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
