package com.metastream.webviewer;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Base64;
import android.util.Log;

import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.MnemonicCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

import fi.iki.elonen.NanoHTTPD;

/**
 * Local offline HTTP server:
 *   - Serves web UI from android_asset/ (copied from /public at build time)
 *   - Provides /api/* endpoints used by connect.html + sign.html
 *
 * WebView loads: http://127.0.0.1:3000/
 * fetch("/api/...") hits this server.
 */
public class AssetHttpServer extends NanoHTTPD {

    private static final String TAG = "ASSET_HTTP";

    private static final int PORT = 3000;
    private static final String ASSET_ROOT_DIR = ""; // root of assets where index.html lives

    private final Context appContext;
    private final AssetManager assets;

    // --- UR collector state (Tool 2) ---
    private volatile String lastUrType = null;
    private volatile byte[] lastUrCbor = null;

    public AssetHttpServer(Context context) {
        super("127.0.0.1", PORT);
        this.appContext = context.getApplicationContext();
        this.assets = this.appContext.getAssets();
    }

    // --------------------------
    // HTTP entrypoint
    // --------------------------
    @Override
    public Response serve(IHTTPSession session) {
        String uri = null;
        Method method = null;

        // We parse body once per request (only for methods that may contain one).
        Map<String, String> files = new HashMap<>();
        String postData = "";

        try {
            method = session.getMethod();
            uri = session.getUri();
            if (uri == null) uri = "/";

            // Preflight should always succeed.
            if (method == Method.OPTIONS) {
                return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json; charset=utf-8", "{}"));
            }

            // Parse body once if applicable
            if (method == Method.POST || method == Method.PUT || method == Method.PATCH) {
                try {
                    session.parseBody(files);
                    postData = readPostDataFromFiles(files);
                } catch (Exception e) {
                    Log.e(TAG, "parseBody failed for " + method + " " + uri, e);
                    if (uri.startsWith("/api/")) {
                        return json(400, "{\"ok\":false,\"error\":\"bad_request\",\"message\":\"Failed to parse request body\"}");
                    }
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "bad request");
                }
            }

            // Log only API calls to keep logcat noise down.
            if (uri.startsWith("/api/")) {
                String qs = session.getQueryParameterString();
                String ct = session.getHeaders() != null ? session.getHeaders().get("content-type") : null;

                Log.i(TAG,
                        "API " + method + " " + uri +
                                (qs != null ? ("?" + qs) : "") +
                                " ct=" + ct +
                                " bodyLen=" + (postData != null ? postData.length() : 0) +
                                " bodyPrefix=" + safePrefix(postData, 400)
                );
            }

            // --------------------------
            // API routes
            // --------------------------
            if (uri.startsWith("/api/")) {
                if ("/api/gen".equals(uri) && method == Method.POST) {
                    return handleApiGen(postData);
                }
                if ("/api/ur/reset".equals(uri) && method == Method.POST) {
                    lastUrType = null;
                    lastUrCbor = null;
                    return jsonOk("{\"ok\":true}");
                }
                if ("/api/ur/part".equals(uri) && method == Method.POST) {
                    return handleApiUrPart(postData);
                }
                if ("/api/ur/decoded".equals(uri) && method == Method.GET) {
                    return handleApiUrDecoded();
                }

                // IMPORTANT: Unknown /api should NEVER return plain text.
                return json(404, "{\"ok\":false,\"error\":\"not_found\",\"message\":\"Unknown API endpoint\"}");
            }

            // --------------------------
            // Static assets over HTTP
            // --------------------------
            if ("/".equals(uri)) uri = "/index.html";
            return serveAsset(uri);

        } catch (Exception e) {
            Log.e(TAG, "Unhandled error in serve() for " + method + " " + uri, e);

            if (uri != null && uri.startsWith("/api/")) {
                return json(500, "{\"ok\":false,\"error\":\"internal_error\",\"message\":\"" + escapeJson(msgOrDefault(e)) + "\"}");
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain",
                    e.getMessage() == null ? "internal error" : e.getMessage());
        }
    }

    private static String msgOrDefault(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? "internal error" : m;
    }

    private static String safePrefix(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(truncated)";
    }

    private static String readPostDataFromFiles(Map<String, String> files) throws Exception {
        // NanoHTTPD variants:
        // 1) files.get("postData") is the raw body STRING
        // 2) files.get("postData") is a TEMP FILE PATH holding the body
        // Some forks use "content" instead.
        String v = files.get("postData");
        if (v == null) v = files.get("content");
        if (v == null) return "";

        // If it looks like a path and exists, read it.
        // Otherwise treat it as the body directly.
        try {
            File f = new File(v);
            if (f.exists() && f.isFile()) {
                FileInputStream fis = new FileInputStream(f);
                try {
                    byte[] buf = readAll(fis);
                    return new String(buf, StandardCharsets.UTF_8);
                } finally {
                    try { fis.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {
            // fall through and treat v as inline body
        }

        return v;
    }

    // --------------------------
    // /api/gen  (Connect Wallet)
    // --------------------------
    private Response handleApiGen(String body) throws Exception {
        String mnemonic = jsonGetString(body, "mnemonic", "");
        String passphrase = jsonGetString(body, "passphrase", "");
        String derivationPath = jsonGetString(body, "path", "m/44'/60'/0'");
        int qrScale = jsonGetInt(body, "qrScale", 8);

        // Normalize mnemonic words
        String wordsNorm = mnemonic.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (wordsNorm.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "Missing mnemonic");
        }

        List<String> wordList = Arrays.asList(wordsNorm.split(" "));
        // Validate BIP39 mnemonic
        try {
            MnemonicCode.INSTANCE.check(wordList);
        } catch (Exception ex) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid BIP39 mnemonic.");
        }

        // Seed + master key
        byte[] seed = MnemonicCode.toSeed(wordList, passphrase == null ? "" : passphrase);
        DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(seed);

        // Derive node + parent
        DeterministicKey node = derivePath(master, derivationPath);
        String parentPath = parentPathOf(derivationPath);
        DeterministicKey parent = "m".equals(parentPath) ? master : derivePath(master, parentPath);

        int masterFp = fingerprintOf(master);
        int parentFp = fingerprintOf(parent);

        // Build CBOR map matching server.js structure
        // top.set(3, publicKey)
        // top.set(4, chainCode)
        // top.set(6, originTagged(304))
        // top.set(8, parentFp)
        // top.set(9, "AirGap - meta")
        com.upokecenter.cbor.CBORObject originTagged = buildOriginTag304(derivationPath, masterFp);

        com.upokecenter.cbor.CBORObject top = com.upokecenter.cbor.CBORObject.NewMap();
        top.Add(3, node.getPubKeyPoint().getEncoded(true));
        top.Add(4, node.getChainCode());
        top.Add(6, originTagged);
        top.Add(8, parentFp);
        top.Add(9, "AirGap - meta");

        byte[] cborBytes = top.EncodeToBytes();

        // UR: crypto-hdkey + minimal bytewords (with CRC32)
        String urBody = bytewordsMinimalWithCrc(cborBytes);
        String urText = ("ur:crypto-hdkey/" + urBody).toUpperCase(Locale.ROOT);

        // QR data URL
        String qrDataUrl = makeQrDataUrl(urText, qrScale);

        String json =
                "{"
                        + "\"ur\":\"" + escapeJson(urText) + "\","
                        + "\"path\":\"" + escapeJson(derivationPath) + "\","
                        + "\"parentPath\":\"" + escapeJson(parentPath) + "\","
                        + "\"masterFpHex\":\"" + String.format(Locale.ROOT, "%08x", masterFp) + "\","
                        + "\"parentFpHex\":\"" + String.format(Locale.ROOT, "%08x", parentFp) + "\","
                        + "\"qrDataUrl\":\"" + escapeJson(qrDataUrl) + "\""
                        + "}";

        return jsonOk(json);
    }

    // --------------------------
    // /api/ur/part  (Sign - collector)
    // Single-part URs: ur:<type>/<bytewordsMinimal>
    // Signing (/api/sign) to be implemented later.
    // --------------------------
    private Response handleApiUrPart(String body) throws Exception {
        String part = jsonGetString(body, "part", "").trim();

        if (part.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "Missing { part } string");
        }

        // Normalize like server.js (lowercase)
        String p = part.toLowerCase(Locale.ROOT);

        // Expect "ur:type/...."
        if (!p.startsWith("ur:") || !p.contains("/")) {
            return jsonOk("{\"status\":\"error\",\"error\":\"invalid ur\"}");
        }

        int slash = p.indexOf('/');
        String type = p.substring(3, slash);
        String bw = p.substring(slash + 1);

        try {
            byte[] decoded = bytewordsMinimalDecodeWithCrc(bw);
            lastUrType = type;
            lastUrCbor = decoded;

            boolean ok = "eth-sign-request".equals(type);

            String json =
                    "{"
                            + "\"status\":\"complete\","
                            + "\"type\":\"" + escapeJson(type) + "\","
                            + "\"cborHex\":\"" + bytesToHex(decoded) + "\","
                            + "\"ok\":" + (ok ? "true" : "false")
                            + "}";
            return jsonOk(json);

        } catch (Exception ex) {
            return jsonOk("{\"status\":\"error\",\"error\":\"" + escapeJson(msgOrDefault(ex)) + "\"}");
        }
    }

    private Response handleApiUrDecoded() {
        if (lastUrType == null || lastUrCbor == null) {
            return jsonOk("{\"ok\":true,\"present\":false}");
        }
        String json =
                "{"
                        + "\"ok\":true,"
                        + "\"present\":true,"
                        + "\"type\":\"" + escapeJson(lastUrType) + "\","
                        + "\"cborHex\":\"" + bytesToHex(lastUrCbor) + "\""
                        + "}";
        return jsonOk(json);
    }

    // --------------------------
    // Static asset serving
    // --------------------------
    private Response serveAsset(String uri) throws Exception {
        // strip leading '/'
        String assetPath = uri.startsWith("/") ? uri.substring(1) : uri;
        if (assetPath.isEmpty()) assetPath = "index.html";

        // prevent path traversal
        if (assetPath.contains("..")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "forbidden");
        }

        String mime = guessMime(assetPath);

        InputStream in = assets.open(ASSET_ROOT_DIR + assetPath);
        byte[] data = readAll(in);

        Response r;
        if (isTextMime(mime)) {
            r = newFixedLengthResponse(Response.Status.OK, mime, new String(data, StandardCharsets.UTF_8));
        } else {
            r = newFixedLengthResponse(Response.Status.OK, mime, new ByteArrayInputStream(data), data.length);
        }

        return cors(r);
    }

    private static boolean isTextMime(String mime) {
        return mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("xml");
    }

    private static String guessMime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    // --------------------------
    // Response helpers (CORS + JSON)
    // --------------------------
    private static Response cors(Response r) {
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        r.addHeader("Access-Control-Allow-Credentials", "true");
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response json(int status, String jsonBody) {
        Response.Status st = Response.Status.lookup(status);
        if (st == null) st = Response.Status.OK;
        Response r = newFixedLengthResponse(st, "application/json; charset=utf-8", jsonBody);
        return cors(r);
    }

    private static Response jsonOk(String jsonBody) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", jsonBody);
        return cors(r);
    }

    private static Response jsonError(Response.Status status, String message) {
        String body = "{\"ok\":false,\"error\":\"" + escapeJson(message) + "\"}";
        Response r = newFixedLengthResponse(status, "application/json; charset=utf-8", body);
        return cors(r);
    }

    // --------------------------
    // Helpers (body read, JSON extract, HD derivation, CBOR origin, Bytewords, QR)
    // --------------------------
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        return out.toByteArray();
    }

    // NOTE: This is a minimal JSON getter (kept to avoid changing UI expectations).
    private static String jsonGetString(String json, String key, String def) {
        if (json == null) return def;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return def;
        int c = json.indexOf(":", i + pat.length());
        if (c < 0) return def;
        int q1 = json.indexOf("\"", c + 1);
        if (q1 < 0) return def;
        int q2 = json.indexOf("\"", q1 + 1);
        if (q2 < 0) return def;
        return json.substring(q1 + 1, q2);
    }

    private static int jsonGetInt(String json, String key, int def) {
        if (json == null) return def;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return def;
        int c = json.indexOf(":", i + pat.length());
        if (c < 0) return def;
        int end = c + 1;
        while (end < json.length() && " \t\r\n".indexOf(json.charAt(end)) >= 0) end++;
        int start = end;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try { return Integer.parseInt(json.substring(start, end)); } catch (Exception e) { return def; }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String parentPathOf(String path) {
        String[] parts = path.split("/");
        if (parts.length <= 2) return "m";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) sb.append("/");
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static DeterministicKey derivePath(DeterministicKey master, String path) {
        String[] parts = path.split("/");
        DeterministicKey cur = master;
        for (int i = 1; i < parts.length; i++) { // skip "m"
            String seg = parts[i];
            boolean hard = seg.endsWith("'");
            int idx = Integer.parseInt(hard ? seg.substring(0, seg.length() - 1) : seg);
            ChildNumber cn = new ChildNumber(idx, hard);
            cur = HDKeyDerivation.deriveChildKey(cur, cn);
        }
        return cur;
    }

    private static int fingerprintOf(DeterministicKey key) {
        byte[] pub = key.getPubKeyPoint().getEncoded(true);
        byte[] h160 = Utils.sha256hash160(pub);
        return ((h160[0] & 0xff) << 24) | ((h160[1] & 0xff) << 16) | ((h160[2] & 0xff) << 8) | (h160[3] & 0xff);
    }

    private static com.upokecenter.cbor.CBORObject buildOriginTag304(String derivationPath, int masterFpU32) {
        com.upokecenter.cbor.CBORObject arr = com.upokecenter.cbor.CBORObject.NewArray();
        String[] parts = derivationPath.split("/");
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i];
            boolean hard = seg.endsWith("'");
            int idx = Integer.parseInt(hard ? seg.substring(0, seg.length() - 1) : seg);
            arr.Add(idx);
            arr.Add(hard);
        }
        com.upokecenter.cbor.CBORObject inner = com.upokecenter.cbor.CBORObject.NewMap();
        inner.Add(1, arr);
        inner.Add(2, masterFpU32);
        return com.upokecenter.cbor.CBORObject.FromObjectAndTag(inner, 304);
    }

    // Bytewords wordlist (0x00..0xFF) from BCR-2020-012 (4-letter words).
    // We use "minimal" encoding: per byte -> 2 chars (first+last of the word), plus CRC32 (4 bytes, big-endian).
    private static final String[] BYTEWORDS = new String[] {
            "able","acid","also","apex","aqua","arch","atom","aunt",
            "away","axis","back","bald","barn","belt","beta","bias",
            "blue","body","brag","brew","bulb","buzz","calm","cash",
            "cats","chef","city","claw","code","cola","cook","cost",
            "crux","curl","cusp","cyan","dark","data","days","deli",
            "dice","diet","door","down","draw","drop","drum","dull",
            "duty","each","easy","echo","edge","epic","even","exam",
            "exit","eyes","fact","fair","fern","figs","film","fish",
            "fizz","flap","flew","flux","foxy","free","frog","fuel",
            "fund","gala","game","gear","gems","gift","girl","glow",
            "good","gray","grim","guru","gush","gyro","half","hang",
            "hard","hawk","heat","help","high","hill","holy","hope",
            "horn","huts","iced","idea","idle","inch","inky","into",
            "iris","iron","item","jade","jazz","join","jolt","jowl",
            "judo","jugs","jump","junk","jury","keep","keno","kept",
            "keys","kick","kiln","king","kite","kiwi","knob","lamb",
            "lava","lazy","leaf","legs","liar","limp","lion","list",
            "logo","loud","love","luau","luck","lung","main","many",
            "math","maze","memo","menu","meow","mild","mint","miss",
            "monk","nail","navy","need","news","next","noon","note",
            "numb","obey","oboe","omit","onyx","open","oval","owls",
            "paid","part","peck","play","plus","poem","pool","pose",
            "puff","puma","purr","quad","quiz","race","ramp","real",
            "redo","rich","road","rock","roof","ruby","ruin","runs",
            "rust","safe","saga","scar","sets","silk","skew","slot",
            "soap","solo","song","stub","surf","swan","taco","task",
            "taxi","tent","tied","time","tiny","toil","tomb","toys",
            "trip","tuna","twin","ugly","undo","unit","urge","user",
            "vast","very","veto","vial","vibe","view","visa","void",
            "vows","wall","wand","warm","wasp","wave","waxy","webs",
            "what","when","whiz","wolf","work","yank","yawn","yell",
            "yoga","yurt","zaps","zero","zest","zinc","zone","zoom"
    };

    private static final char[] BYTEWORDS_MIN_FIRST = new char[256];
    private static final char[] BYTEWORDS_MIN_LAST  = new char[256];

    static {
        for (int i = 0; i < 256; i++) {
            String w = BYTEWORDS[i];
            BYTEWORDS_MIN_FIRST[i] = w.charAt(0);
            BYTEWORDS_MIN_LAST[i]  = w.charAt(w.length() - 1);
        }
    }

    private static String bytewordsMinimalWithCrc(byte[] data) {
        byte[] withCrc = appendCrc32BigEndian(data);
        StringBuilder sb = new StringBuilder(withCrc.length * 2);
        for (byte b : withCrc) {
            int u = b & 0xff;
            sb.append(BYTEWORDS_MIN_FIRST[u]).append(BYTEWORDS_MIN_LAST[u]);
        }
        return sb.toString();
    }

    private static byte[] bytewordsMinimalDecodeWithCrc(String minimal) throws Exception {
        if ((minimal.length() % 2) != 0) throw new Exception("invalid bytewords length");
        int n = minimal.length() / 2;
        byte[] out = new byte[n];

        int[] rev = new int[26 * 26];
        Arrays.fill(rev, -1);
        for (int i = 0; i < 256; i++) {
            int idx = ((BYTEWORDS_MIN_FIRST[i] - 'a') * 26) + (BYTEWORDS_MIN_LAST[i] - 'a');
            if (idx >= 0 && idx < rev.length) rev[idx] = i;
        }

        for (int i = 0; i < n; i++) {
            char c1 = minimal.charAt(i * 2);
            char c2 = minimal.charAt(i * 2 + 1);
            if (c1 < 'a' || c1 > 'z' || c2 < 'a' || c2 > 'z') throw new Exception("invalid bytewords chars");
            int idx = ((c1 - 'a') * 26) + (c2 - 'a');
            int v = rev[idx];
            if (v < 0) throw new Exception("invalid bytewords code");
            out[i] = (byte) v;
        }

        if (out.length < 4) throw new Exception("missing crc32");
        byte[] payload = Arrays.copyOfRange(out, 0, out.length - 4);
        byte[] crc = Arrays.copyOfRange(out, out.length - 4, out.length);

        CRC32 c = new CRC32();
        c.update(payload);
        long want = c.getValue();
        long got = ((crc[0] & 0xffL) << 24) | ((crc[1] & 0xffL) << 16) | ((crc[2] & 0xffL) << 8) | (crc[3] & 0xffL);

        if ((want & 0xffffffffL) != (got & 0xffffffffL)) throw new Exception("crc32 mismatch");
        return payload;
    }

    private static byte[] appendCrc32BigEndian(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        long v = c.getValue();
        byte[] out = Arrays.copyOf(data, data.length + 4);
        out[data.length]     = (byte) ((v >>> 24) & 0xff);
        out[data.length + 1] = (byte) ((v >>> 16) & 0xff);
        out[data.length + 2] = (byte) ((v >>> 8) & 0xff);
        out[data.length + 3] = (byte) (v & 0xff);
        return out;
    }

    private static String makeQrDataUrl(String text, int scale) throws Exception {
        int s = Math.max(3, Math.min(12, scale));
        int size = 256 * s / 8;

        com.google.zxing.common.BitMatrix bm =
                new com.google.zxing.qrcode.QRCodeWriter().encode(
                        text,
                        com.google.zxing.BarcodeFormat.QR_CODE,
                        size,
                        size
                );

        int w = bm.getWidth();
        int h = bm.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int off = y * w;
            for (int x = 0; x < w; x++) {
                pixels[off + x] = bm.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        byte[] png = out.toByteArray();

        return "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }
}
