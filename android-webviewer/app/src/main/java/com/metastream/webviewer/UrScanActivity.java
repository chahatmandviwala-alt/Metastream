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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sparrowwallet.hummingbird.UR;
import com.sparrowwallet.hummingbird.URDecoder;

public class UrScanActivity extends AppCompatActivity {

    public static final String EXTRA_UR_TYPE = "ur_type";
    public static final String EXTRA_UR_CBOR = "ur_cbor";

    private PreviewView previewView;
    private TextView progressText;

    private ExecutorService analysisExecutor;

    private final URDecoder urDecoder = new URDecoder();

    // ZXing reader configured for QR only
    private final MultiFormatReader reader = new MultiFormatReader();

    // Dedup based on exact part string; Hummingbird also tolerates repeats, but this reduces work.
    private String lastPart = null;

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

        startCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (analysisExecutor != null) analysisExecutor.shutdownNow();
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

            // Convert Y plane (luma) to byte[] for ZXing
            ByteBuffer yBuf = image.getPlanes()[0].getBuffer();
            byte[] y = new byte[yBuf.remaining()];
            yBuf.get(y);

            int width = image.getWidth();
            int height = image.getHeight();

            // Fixed ROI: center square ~70% of the smaller dimension
            Rect roi = centerSquareRoi(width, height, 0.70f);

            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    y,
                    width,
                    height,
                    roi.left,
                    roi.top,
                    roi.width(),
                    roi.height(),
                    false
            );

            // Try normal + inverted in a deterministic manner
            String text = tryDecode(source);
            if (text == null) {
                // Invert: ZXing supports it via InvertedLuminanceSource
                LuminanceSource inverted = source.invert();
                text = tryDecode(inverted);
            }

            if (text != null) {
                String part = text.trim();
                // We only care about UR fragments
                if (part.regionMatches(true, 0, "ur:", 0, 3)) {
                    // Optional exact-string dedup to reduce useless repeats
                    if (!part.equals(lastPart)) {
                        lastPart = part;
                        urDecoder.receivePart(part);

                        URDecoder.Result res = urDecoder.getResult();
                        if (res != null && res.type == URDecoder.ResultType.SUCCESS) {
                            UR ur = res.ur;
                            byte[] cbor = ur.toBytes();
                            String type = ur.getType();

                            finishSuccess(type, cbor);
                        } else {
                            // Update progress text (best-effort; API does not guarantee total count)
                            runOnUiThread(() -> progressText.setText("Collecting UR fragments…"));
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

    private String tryDecode(LuminanceSource src) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(src));
            Result r = reader.decodeWithState(bitmap);
            return r.getText();
        } catch (NotFoundException e) {
            return null;
        } finally {
            reader.reset();
        }
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
