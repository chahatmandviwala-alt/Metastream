package com.metastream.webviewer;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class AssetHttpServer extends NanoHTTPD {

    private static final String TAG = "AssetHttpServer";
    private final AssetManager assets;

    public AssetHttpServer(int port, AssetManager assets) {
        super(port);
        this.assets = assets;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Log.d(TAG, "Request: " + uri);

        // --- API PLACEHOLDER (we will wire logic next) ---
        // --- API (always JSON, always parses body to avoid NanoHTTPD "Bad request") ---
        if (uri.startsWith("/api/")) {
            try {
                // IMPORTANT: parse request body, otherwise NanoHTTPD may emit "Bad request"
                Map<String, String> files = new HashMap<>();
                session.parseBody(files); // populates files.get("postData") for JSON POSTs
            } catch (Exception ignored) {
                // Even if parsing fails, we still return JSON below.
            }

            String body = "{\"ok\":false,\"error\":\"API not implemented on Android yet\",\"path\":\""
                    + uri.replace("\"", "\\\"")
                    + "\"}";

            Response r = newFixedLengthResponse(Response.Status.NOT_IMPLEMENTED, "application/json", body);
            r.addHeader("Access-Control-Allow-Origin", "*");
            r.addHeader("Access-Control-Allow-Headers", "content-type");
            r.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
            return r;
        }

        // --- Static files from assets ---
        if (uri.equals("/")) uri = "/index.html";

        String assetPath = uri.startsWith("/") ? uri.substring(1) : uri;

        try {
            InputStream is = assets.open(assetPath);
            String mime = getMimeType(assetPath);
            return newChunkedResponse(Response.Status.OK, mime, is);
        } catch (IOException e) {
            return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Not found: " + uri
            );
        }
    }

    private String getMimeType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
