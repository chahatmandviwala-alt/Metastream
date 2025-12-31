package com.metastream.webviewer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Size;

import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.common.InputImage;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.appcompat.app.AppCompatActivity;

public class UrScanActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ExecutorService cameraExecutor;

    // De-dupe on the phone side to avoid reposting the same frame endlessly
    private final HashSet<String> seenSeq = new HashSet<>();

    private static final String API_PART_URL = "http://127.0.0.1:3000/api/ur/part";
    private static final String API_DECODED_URL = "http://127.0.0.1:3000/api/ur/decoded";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ur_scan);

        previewView = findViewById(R.id.preview_view);
        cameraExecutor = Executors.newSingleThreadExecutor();

        startCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new Size(1280, 720))
                        .build();

                BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build();
                BarcodeScanner scanner = BarcodeScanning.getClient(options);

                analysis.setAnalyzer(cameraExecutor, image -> analyzeImage(scanner, image));

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis);

            } catch (Exception e) {
                // If camera fails, just exit to avoid trapping user
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(BarcodeScanner scanner, ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
        );

        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode b : barcodes) {
                        String raw = b.getRawValue();
                        if (raw == null) continue;

                        String text = raw.trim();
                        if (text.isEmpty()) continue;

                        String lower = text.toLowerCase(Locale.ROOT);
                        if (!lower.startsWith("ur:")) continue;

                        // De-dupe on total-index if present: ur:TYPE/<total>-<idx>/...
                        String key = extractSeqKey(lower);
                        if (key != null) {
                            synchronized (seenSeq) {
                                if (!seenSeq.add(key)) continue;
                            }
                        }

                        // Post to local server (same contract as sign.html)
                        new Thread(() -> {
                            try {
                                postPart(text);
                                if (isDecodedAvailable()) {
                                    runOnUiThread(() -> {
                                        setResult(Activity.RESULT_OK, new Intent());
                                        finish();
                                    });
                                }
                            } catch (Exception ignored) {}
                        }).start();
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private static String extractSeqKey(String lower) {
        // ur:type/299-5/...
        int firstSlash = lower.indexOf('/');
        if (firstSlash <= 0) return null;
        int secondSlash = lower.indexOf('/', firstSlash + 1);
        if (secondSlash <= firstSlash) return null;

        String seq = lower.substring(firstSlash + 1, secondSlash); // "299-5"
        if (!seq.contains("-")) return null;
        return seq;
    }

    private static void postPart(String part) throws Exception {
        URL url = new URL(API_PART_URL);
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

        // Drain response
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
