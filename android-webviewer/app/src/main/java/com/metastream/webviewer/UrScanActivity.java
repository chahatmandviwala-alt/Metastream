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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.PlanarYUVLuminanceSource;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;

import androidx.appcompat.app.AppCompatActivity;

public class UrScanActivity extends AppCompatActivity {

    private PreviewView previewView;
    private ExecutorService cameraExecutor;

    // De-dupe on the phone side to avoid reposting the same frame endlessly
    private final HashSet<String> seenSeq = new HashSet<>();

    private volatile int lastIndexSeen = -1;
    private volatile int sameIndexStreak = 0;

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

                analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

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
    private void analyzeImage(ImageProxy imageProxy) {
        try {
            if (imageProxy.getImage() == null) return;

            // Use Y plane directly (luminance)
            ImageProxy.PlaneProxy yPlane = imageProxy.getPlanes()[0];
            ByteBuffer yBuffer = yPlane.getBuffer();

            int width = imageProxy.getWidth();
            int height = imageProxy.getHeight();

            int rowStride = yPlane.getRowStride();
            int pixelStride = yPlane.getPixelStride(); // usually 1 for Y plane

            // Copy into a tight width*height luminance array (ZXing expects no row padding)
            byte[] yData = new byte[width * height];
            byte[] row = new byte[rowStride];

            yBuffer.rewind();
            for (int r = 0; r < height; r++) {
                int rowStart = r * rowStride;

                // Read one full row (including padding) into temp buffer
                yBuffer.position(rowStart);
                yBuffer.get(row, 0, Math.min(rowStride, yBuffer.remaining()));

                if (pixelStride == 1) {
                    System.arraycopy(row, 0, yData, r * width, width);
                } else {
                    // Defensive path (rare for Y plane)
                    for (int c = 0; c < width; c++) {
                        yData[r * width + c] = row[c * pixelStride];
                    }
                }
            }

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    yData,
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    false
            );

            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            MultiFormatReader reader = new MultiFormatReader();
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            reader.setHints(hints);

            Result result;
            try {
                result = reader.decodeWithState(bitmap);
            } catch (NotFoundException e) {
                return; // no QR in this frame
            }

            if (result == null || result.getText() == null) return;

            String text = result.getText().trim();
            if (text.isEmpty()) return;

            String lower = text.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("ur:")) return;

            // De-dupe by total-index if present: ur:type/total-index/...
            String key = extractSeqKey(lower);
            if (key != null) {

                // Only proceed if this is a NEW frame index
                boolean isNew;
                synchronized (seenSeq) {
                    isNew = seenSeq.add(key);
                }
                if (!isNew) return;

                int idx = parseIndexFromSeqKey(key);
                if (idx >= 0) {
                    // Break sampling lock if we keep seeing the same index
                    if (idx == lastIndexSeen) {
                        sameIndexStreak++;
                    } else {
                        lastIndexSeen = idx;
                        sameIndexStreak = 0;
                    }

                    if (sameIndexStreak >= 6) {
                        sameIndexStreak = 0;
                        try {
                            Thread.sleep(110); // phase shift vs animation
                        } catch (InterruptedException ignored) {}
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
                    }
                } catch (Exception ignored) {}
            }).start();

        } finally {
            imageProxy.close();
        }
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

    private static int parseIndexFromSeqKey(String seqKey) {
        // seqKey format: "843-5"
        int dash = seqKey.indexOf('-');
        if (dash <= 0) return -1;
        try {
            return Integer.parseInt(seqKey.substring(dash + 1));
        } catch (Exception e) {
            return -1;
        }
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
