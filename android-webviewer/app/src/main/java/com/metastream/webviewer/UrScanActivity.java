package com.metastream.webviewer;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Size;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.camera.core.Preview;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sparrowwallet.hummingbird.UR;
import com.sparrowwallet.hummingbird.URDecoder;
import com.sparrowwallet.hummingbird.ResultType;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class UrScanActivity extends AppCompatActivity {

    private static final int REQ_CAMERA_PERMISSION = 10;
    
    public static final String EXTRA_UR_TYPE = "ur_type";
    public static final String EXTRA_UR_CBOR = "ur_cbor";

    private PreviewView previewView;
    private TextView progressText;

    private ExecutorService analysisExecutor;

    private final URDecoder urDecoder = new URDecoder();

    // ZXing reader configured for QR only
    private final MultiFormatReader reader = new MultiFormatReader();

    // Adaptive ROI lock + dedup
    private Rect lockedRoi = null;
    private long lockedRoiUntilMs = 0L;
    private static final long ROI_LOCK_MS = 2500;

    private final Set<String> seenParts = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ur_scan);

        previewView = findViewById(R.id.previewView);
        progressText = findViewById(R.id.progressText);

        analysisExecutor = Executors.newSingleThreadExecutor();

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        // Keep charset flexible; UR is ASCII-ish but wallets sometimes embed lowercase/uppercase.
        hints.put(DecodeHintType.CHARACTER_SET, "ISO-8859-1");
        reader.setHints(hints);

        ensureCameraPermissionThenStart();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (analysisExecutor != null) analysisExecutor.shutdownNow();
    }

    private void ensureCameraPermissionThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            progressText.setText("Requesting camera permission…");
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQ_CAMERA_PERMISSION
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                // Return to WebView; optionally alert user
                finishWithError("Camera permission denied");
            }
        }
    }
    
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        // 720p is a good deterministic tradeoff; avoids huge CPU spikes.
                        .setTargetResolution(new Size(1280, 720))
                        .build();

                analysis.setAnalyzer(analysisExecutor, this::analyze);

                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, preview, analysis);

            } catch (Exception e) {
                runOnUiThread(() -> progressText.setText("Camera init failed: " + e.getMessage()));
                // Optionally also finish:
                finishWithError("Camera init failed: " + e.getMessage());
            }
        }, getMainExecutor());
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyze(ImageProxy image) {
        try {
            if (urDecoder.getResult() != null) {
                image.close();
                return;
            }

            int rotation = image.getImageInfo().getRotationDegrees();

            // Read luma
            ByteBuffer yBuf = image.getPlanes()[0].getBuffer();
            byte[] y = new byte[yBuf.remaining()];
            yBuf.get(y);

            int width = image.getWidth();
            int height = image.getHeight();

            // Rotate luma to match upright orientation (critical for ZXing reliability)
            RotatedLuma rotated = rotateLuma(y, width, height, rotation);

            // Decide ROI: full frame until we have a lock; then use locked ROI
            Rect roi;
            long now = System.currentTimeMillis();
            if (lockedRoi != null && now < lockedRoiUntilMs) {
                roi = lockedRoi;
            } else {
                roi = new Rect(0, 0, rotated.width, rotated.height);
                lockedRoi = null;
            }

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    rotated.data,
                    rotated.width,
                    rotated.height,
                    roi.left,
                    roi.top,
                    roi.width(),
                    roi.height(),
                    false
            );

            // Try normal + inverted
            Result zxingResult = tryDecodeResult(source);
            if (zxingResult == null) {
                zxingResult = tryDecodeResult(source.invert());
            }

            if (zxingResult != null) {
                String text = zxingResult.getText() != null ? zxingResult.getText().trim() : null;

                // If we decoded something QR-ish, compute and lock ROI around QR for a short period
                Rect newLock = computeRoiFromResultPoints(zxingResult, rotated.width, rotated.height);
                if (newLock != null) {
                    lockedRoi = newLock;
                    lockedRoiUntilMs = now + ROI_LOCK_MS;
                }

                if (text != null && text.regionMatches(true, 0, "ur:", 0, 3)) {
                    // Dedup on fragment text to avoid spinning on repeats
                    if (seenParts.add(text)) {
                        urDecoder.receivePart(text);

                        URDecoder.Result res = urDecoder.getResult();
                        if (res != null && res.type == ResultType.SUCCESS) {
                            UR ur = res.ur;
                            finishSuccess(ur.getType(), ur.toBytes());
                            return;
                        } else {
                            runOnUiThread(() ->
                                    progressText.setText("Collecting UR fragments… (" + seenParts.size() + " unique)")
                            );
                        }
                    }
                }
            }

        } catch (Exception ignored) {
            // Keep loop tight; ignore per-frame failures
        } finally {
            image.close();
        }
    }

    private Result tryDecodeResult(LuminanceSource src) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(src));
            return reader.decodeWithState(bitmap);
        } catch (NotFoundException e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private static final class RotatedLuma {
        final byte[] data;
        final int width;
        final int height;
        RotatedLuma(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }

    private RotatedLuma rotateLuma(byte[] y, int width, int height, int rotationDegrees) {
        if (rotationDegrees == 0) {
            return new RotatedLuma(y, width, height);
        }

        if (rotationDegrees == 180) {
            byte[] out = new byte[y.length];
            for (int i = 0; i < y.length; i++) {
                out[i] = y[y.length - 1 - i];
            }
            return new RotatedLuma(out, width, height);
        }

        if (rotationDegrees == 90) {
            // output dims: height x width
            byte[] out = new byte[y.length];
            int outW = height;
            int outH = width;
            // (x,y) -> (outX,outY) where outX = outW-1-y, outY = x
            for (int x = 0; x < width; x++) {
                for (int y0 = 0; y0 < height; y0++) {
                    int inIndex = y0 * width + x;
                    int outX = outW - 1 - y0;
                    int outY = x;
                    int outIndex = outY * outW + outX;
                    out[outIndex] = y[inIndex];
                }
            }
            return new RotatedLuma(out, outW, outH);
        }

        if (rotationDegrees == 270) {
            // output dims: height x width
            byte[] out = new byte[y.length];
            int outW = height;
            int outH = width;
            // (x,y) -> (outX,outY) where outX = y, outY = outH-1-x
            for (int x = 0; x < width; x++) {
                for (int y0 = 0; y0 < height; y0++) {
                    int inIndex = y0 * width + x;
                    int outX = y0;
                    int outY = outH - 1 - x;
                    int outIndex = outY * outW + outX;
                    out[outIndex] = y[inIndex];
                }
            }
            return new RotatedLuma(out, outW, outH);
        }

        // Fallback
        return new RotatedLuma(y, width, height);
    }

    private Rect computeRoiFromResultPoints(Result result, int imgW, int imgH) {
        ResultPoint[] pts = result.getResultPoints();
        if (pts == null || pts.length == 0) return null;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = 0, maxY = 0;

        for (ResultPoint p : pts) {
            if (p == null) continue;
            float x = p.getX();
            float y = p.getY();
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
        }

        if (minX == Float.MAX_VALUE) return null;

        // Expand box (QR finder points cover corners; expand to include full code)
        float padX = (maxX - minX) * 0.6f;
        float padY = (maxY - minY) * 0.6f;

        int left = clamp((int)Math.floor(minX - padX), 0, imgW - 1);
        int top = clamp((int)Math.floor(minY - padY), 0, imgH - 1);
        int right = clamp((int)Math.ceil(maxX + padX), 0, imgW);
        int bottom = clamp((int)Math.ceil(maxY + padY), 0, imgH);

        // Avoid degenerate ROI
        if (right - left < 100 || bottom - top < 100) return null;

        return new Rect(left, top, right, bottom);
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private Rect centerSquareRoi(int w, int h, float scale) {
        int side = (int)(Math.min(w, h) * scale);
        int left = (w - side) / 2;
        int top = (h - side) / 2;
        return new Rect(left, top, left + side, top + side);
    }

    private void finishSuccess(String type, byte[] cbor) {
        Intent data = new Intent();
        data.putExtra(EXTRA_UR_TYPE, type);
        data.putExtra(EXTRA_UR_CBOR, cbor);
        setResult(RESULT_OK, data);
        finish();
    }

    private void finishWithError(String msg) {
        Intent data = new Intent();
        data.putExtra("error", msg);
        setResult(RESULT_CANCELED, data);
        finish();
    }
}
