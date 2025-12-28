package com.metastream.webviewer;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.response.FixedStatusCode;
import org.nanohttpd.protocols.http.response.IStatus;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class AssetHttpServer extends NanoHTTPD {
    private static final String TAG = "AssetHttpServer";

    private final AssetManager assets;

    public AssetHttpServer(Context ctx, int port) {
        super("127.0.0.1", port);
        this.assets = ctx.getAssets();
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            final String method = session.getMethod().name();
            final String uriRaw = session.getUri() == null ? "/" : session.getUri();
            final String uri = uriRaw.isEmpty() ? "/" : uriRaw;

            // Basic CORS (helps fetch() from the WebView)
            if ("OPTIONS".equalsIgnoreCase(method)) {
                Response r = Response.newFixedLengthResponse(Status.OK, "text/plain", "");
                addCors(r);
                return r;
            }

            // ---- API endpoints are NOT implemented here (yet) ----
            // Make the failure deterministic and JSON (so your HTML can show a proper error).
            if (uri.startsWith("/api/")) {
                String body = "{\"error\":\"API not implemented on Android yet\",\"path\":\"" + escapeJson(uri) + "\"}";
                Response r = Response.newFixedLengthResponse(Status.NOT_IMPLEMENTED, "application/json; charset=utf-8", body);
                addCors(r);
                return r;
            }

            // ---- Static assets ----
            // Serve "/" as index.html
            String assetPath = uri.equals("/") ? "index.html" : uri.substring(1); // strip leading '/'
            assetPath = normalize(assetPath);

            // Prevent escaping out of assets root
            if (assetPath.contains("..")) {
                Response r = Response.newFixedLengthResponse(Status.FORBIDDEN, "text/plain; charset=utf-8", "Forbidden");
                addCors(r);
                return r;
            }

            InputStream is = assets.open(assetPath, AssetManager.ACCESS_STREAMING);
            String mime = guessMime(assetPath);

            Response r = Response.newChunkedResponse(Status.OK, mime, is);
            // Cache-busting off for dev; you can tune later
            r.addHeader("Cache-Control", "no-store");
            addCors(r);
            return r;

        } catch (IOException notFound) {
            Response r = Response.newFixedLengthResponse(Status.NOT_FOUND, "text/plain; charset=utf-8", "Not found");
            addCors(r);
            return r;
        } catch (Exception e) {
            Log.e(TAG, "Server error", e);
            Response r = Response.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "Internal error");
            addCors(r);
            return r;
        }
    }

    private static void addCors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String normalize(String p) {
        // Remove any leading slashes and collapse duplicate slashes
        String s = p;
        while (s.startsWith("/")) s = s.substring(1);
        s = s.replaceAll("/{2,}", "/");
        if (s.isEmpty()) return "index.html";
        return s;
    }

    private static String guessMime(String path) {
        String p = path.toLowerCase(Locale.US);

        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
