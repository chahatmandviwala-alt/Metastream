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

        // Parse body once per request for methods that may contain one.
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

            // Log only API calls to keep noise down.
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

                // Unknown /api should NEVER return plain text.
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
        // 3: publicKey, 4: chainCode, 6: originTagged(304), 8: parentFp, 9: "AirGap - meta"
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
    // Single-part URs: ur:<type>/<bytewords>
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
            // NOTE: This currently expects the "minimal" 2-letter encoding.
            // Your scanned QR looks like standard bytewords; decoding may fail until we implement standard bytewords.
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
        try {
            if (lastUrType == null || lastUrCbor == null) {
                return jsonOk("{\"error\":\"No assembled UR yet. Scan the QR first.\"}");
            }
            if (!"eth-sign-request".equals(lastUrType)) {
                return jsonOk("{\"error\":\"Last UR type was " + escapeJson(lastUrType) + ", expected eth-sign-request.\"}");
            }

            com.upokecenter.cbor.CBORObject obj = com.upokecenter.cbor.CBORObject.DecodeFromBytes(lastUrCbor);
            if (obj == null || obj.getType() != com.upokecenter.cbor.CBORType.Map) {
                return jsonOk("{\"error\":\"Invalid CBOR: expected map.\"}");
            }

            byte[] requestId = getBytesByKey(obj, 1);
            byte[] signData = getBytesByKey(obj, 2);
            Integer dataType = getIntByKey(obj, 3);
            Long chainId = getLongByKey(obj, 4);

            if (signData == null || signData.length == 0) signData = findLikelySignData(obj);
            if (dataType == null) dataType = findLikelyDataType(obj);

            if (signData == null || signData.length == 0) {
                return jsonOk("{\"error\":\"Could not locate signData in CBOR.\"}");
            }
            if (dataType == null) {
                return jsonOk("{\"error\":\"Could not locate dataType in CBOR.\"}");
            }

            String requestIdHex = requestId != null ? bytesToHex(requestId) : null;

            if (dataType == 4) {
                // EIP-1559 typed transaction: 0x02 || rlp(list)
                if ((signData[0] & 0xff) != 0x02) {
                    return jsonOk("{\"error\":\"Expected typed tx prefix 0x02 but did not find it.\"}");
                }
                byte[] rlpPayload = Arrays.copyOfRange(signData, 1, signData.length);
                List<byte[]> fields = rlpDecodeList(rlpPayload);

                if (fields.size() < 8) {
                    return jsonOk("{\"error\":\"EIP-1559 RLP decode returned too few fields.\"}");
                }

                long chainIdVal = (chainId != null) ? chainId : bigIntFromBytes(fields.get(0)).longValue();
                long nonce = bigIntFromBytes(fields.get(1)).longValue();
                java.math.BigInteger maxPriWei = bigIntFromBytes(fields.get(2));
                java.math.BigInteger maxFeeWei = bigIntFromBytes(fields.get(3));
                long gasLimit = bigIntFromBytes(fields.get(4)).longValue();
                byte[] toBuf = fields.get(5);
                java.math.BigInteger valueWei = bigIntFromBytes(fields.get(6));
                byte[] dataBuf = fields.get(7);

                String toAddr = (toBuf == null || toBuf.length == 0) ? null : ("0x" + bytesToHex(toBuf));
                String valueEth = formatUnits(valueWei, 18);

                Action action = classifyAction(chainIdVal, toAddr, valueWei, dataBuf);

                String txJson =
                        "{"
                                + "\"type\":\"eip-1559\","
                                + "\"chainId\":" + chainIdVal + ","
                                + "\"nonce\":" + nonce + ","
                                + "\"maxPriorityFeePerGasWei\":\"" + maxPriWei.toString() + "\","
                                + "\"maxPriorityFeePerGas\":\"" + escapeJson(formatGwei(maxPriWei)) + "\","
                                + "\"maxFeePerGasWei\":\"" + maxFeeWei.toString() + "\","
                                + "\"maxFeePerGas\":\"" + escapeJson(formatGwei(maxFeeWei)) + "\","
                                + "\"gasLimit\":" + gasLimit + ","
                                + "\"to\":" + (toAddr == null ? "null" : ("\"" + escapeJson(toAddr) + "\"")) + ","
                                + "\"valueWei\":\"" + valueWei.toString() + "\","
                                + "\"valueEth\":\"" + escapeJson(valueEth) + "\""
                                + "}";

                String humanJson = buildHumanJson(chainIdVal, toAddr, valueEth, action);

                String out =
                        "{"
                                + "\"urType\":\"eth-sign-request\","
                                + "\"dataType\":" + dataType + ","
                                + "\"chainId\":" + chainIdVal + ","
                                + "\"requestIdHex\":" + (requestIdHex == null ? "null" : ("\"" + requestIdHex + "\"")) + ","
                                + "\"tx\":" + txJson + ","
                                + "\"human\":" + humanJson
                                + "}";

                return jsonOk(out);
            }

            if (dataType == 1) {
                // Legacy transaction: rlp(list)
                List<byte[]> fields = rlpDecodeList(signData);

                if (fields.size() < 6) {
                    return jsonOk("{\"error\":\"Legacy RLP decode returned too few fields.\"}");
                }

                long nonce = bigIntFromBytes(fields.get(0)).longValue();
                java.math.BigInteger gasPriceWei = bigIntFromBytes(fields.get(1));
                long gasLimit = bigIntFromBytes(fields.get(2)).longValue();
                byte[] toBuf = fields.get(3);
                java.math.BigInteger valueWei = bigIntFromBytes(fields.get(4));
                byte[] dataBuf = fields.get(5);

                long chainIdVal = (chainId != null) ? chainId : 0L;
                String toAddr = (toBuf == null || toBuf.length == 0) ? null : ("0x" + bytesToHex(toBuf));
                String valueEth = formatUnits(valueWei, 18);

                Action action = classifyAction(chainIdVal, toAddr, valueWei, dataBuf);

                String txJson =
                        "{"
                                + "\"type\":\"legacy\","
                                + "\"chainId\":" + (chainIdVal == 0 ? "null" : String.valueOf(chainIdVal)) + ","
                                + "\"nonce\":" + nonce + ","
                                + "\"gasPriceWei\":\"" + gasPriceWei.toString() + "\","
                                + "\"gasPrice\":\"" + escapeJson(formatGwei(gasPriceWei)) + "\","
                                + "\"gasLimit\":" + gasLimit + ","
                                + "\"to\":" + (toAddr == null ? "null" : ("\"" + escapeJson(toAddr) + "\"")) + ","
                                + "\"valueWei\":\"" + valueWei.toString() + "\","
                                + "\"valueEth\":\"" + escapeJson(valueEth) + "\""
                                + "}";

                String humanJson = buildHumanJson(chainIdVal, toAddr, valueEth, action);

                String out =
                        "{"
                                + "\"urType\":\"eth-sign-request\","
                                + "\"dataType\":" + dataType + ","
                                + "\"chainId\":" + (chainIdVal == 0 ? "null" : String.valueOf(chainIdVal)) + ","
                                + "\"requestIdHex\":" + (requestIdHex == null ? "null" : ("\"" + requestIdHex + "\"")) + ","
                                + "\"tx\":" + txJson + ","
                                + "\"human\":" + humanJson
                                + "}";

                return jsonOk(out);
            }

            return jsonOk("{\"error\":\"Unsupported dataType: " + dataType + "\"}");

        } catch (Exception e) {
            return jsonOk("{\"error\":\"" + escapeJson(e.getMessage() == null ? "decode error" : e.getMessage()) + "\"}");
        }
    }

    // --------------------------
    // ETH SignRequest decoding helpers
    // --------------------------

    private static byte[] getBytesByKey(com.upokecenter.cbor.CBORObject map, int key) {
        try {
            com.upokecenter.cbor.CBORObject v = map.get(com.upokecenter.cbor.CBORObject.FromObject(key));
            if (v == null) return null;
            if (v.getType() == com.upokecenter.cbor.CBORType.ByteString) return v.GetByteString();
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer getIntByKey(com.upokecenter.cbor.CBORObject map, int key) {
        try {
            com.upokecenter.cbor.CBORObject v = map.get(com.upokecenter.cbor.CBORObject.FromObject(key));
            if (v == null) return null;
            if (v.isNumber()) return v.AsInt32();
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long getLongByKey(com.upokecenter.cbor.CBORObject map, int key) {
        try {
            com.upokecenter.cbor.CBORObject v = map.get(com.upokecenter.cbor.CBORObject.FromObject(key));
            if (v == null) return null;
            if (v.isNumber()) return v.AsInt64();
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Fallback: search CBOR map for a bytes field that looks like tx sign bytes.
     * Heuristics: length > 20 and either starts with 0x02 (typed tx) or is an RLP list (0xc0+).
     */
    private static byte[] findLikelySignData(com.upokecenter.cbor.CBORObject map) {
        try {
            for (com.upokecenter.cbor.CBORObject k : map.getKeys()) {
                com.upokecenter.cbor.CBORObject v = map.get(k);
                // FIX: single '{' only (your uploaded file had '{{' here)
                if (v != null && v.getType() == com.upokecenter.cbor.CBORType.ByteString) {
                    byte[] b = v.GetByteString();
                    if (b != null && b.length > 20) {
                        int first = b[0] & 0xff;
                        if (first == 0x02 || first >= 0xc0) return b;
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Fallback: find a small integer in the map that matches known data types (1 or 4). */
    private static Integer findLikelyDataType(com.upokecenter.cbor.CBORObject map) {
        try {
            for (com.upokecenter.cbor.CBORObject k : map.getKeys()) {
                com.upokecenter.cbor.CBORObject v = map.get(k);
                if (v != null && v.isNumber()) {
                    int n = v.AsInt32();
                    if (n == 1 || n == 4) return n;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static java.math.BigInteger bigIntFromBytes(byte[] b) {
        if (b == null || b.length == 0) return java.math.BigInteger.ZERO;
        return new java.math.BigInteger(1, b);
    }

    // Minimal RLP list decoder returning list of byte[] items (strings). Sufficient for tx decoding.
    private static List<byte[]> rlpDecodeList(byte[] rlp) throws Exception {
        if (rlp == null || rlp.length == 0) throw new Exception("empty rlp");
        int[] pos = new int[]{0};
        RlpItem item = rlpDecodeItem(rlp, pos);
        if (!item.isList || item.list == null) throw new Exception("rlp root is not list");
        return item.list;
    }

    private static class RlpItem {
        boolean isList;
        byte[] bytes;
        List<byte[]> list;
    }

    private static RlpItem rlpDecodeItem(byte[] data, int[] posRef) throws Exception {
        int pos = posRef[0];
        if (pos >= data.length) throw new Exception("rlp overflow");

        int prefix = data[pos] & 0xff;
        RlpItem out = new RlpItem();

        if (prefix <= 0x7f) {
            out.isList = false;
            out.bytes = new byte[]{data[pos]};
            posRef[0] = pos + 1;
            return out;
        }

        if (prefix <= 0xb7) {
            int len = prefix - 0x80;
            if (len == 0) out.bytes = new byte[0];
            else {
                ensureAvail(data, pos + 1, len);
                out.bytes = Arrays.copyOfRange(data, pos + 1, pos + 1 + len);
            }
            out.isList = false;
            posRef[0] = pos + 1 + len;
            return out;
        }

        if (prefix <= 0xbf) {
            int lenOfLen = prefix - 0xb7;
            ensureAvail(data, pos + 1, lenOfLen);
            int len = readBigEndianInt(data, pos + 1, lenOfLen);
            ensureAvail(data, pos + 1 + lenOfLen, len);
            out.bytes = Arrays.copyOfRange(data, pos + 1 + lenOfLen, pos + 1 + lenOfLen + len);
            out.isList = false;
            posRef[0] = pos + 1 + lenOfLen + len;
            return out;
        }

        if (prefix <= 0xf7) {
            int listLen = prefix - 0xc0;
            ensureAvail(data, pos + 1, listLen);
            int end = pos + 1 + listLen;

            out.isList = true;
            java.util.ArrayList<byte[]> items = new java.util.ArrayList<>();
            posRef[0] = pos + 1;

            while (posRef[0] < end) {
                RlpItem child = rlpDecodeItem(data, posRef);
                if (child.isList) {
                    items.add(new byte[0]);
                } else {
                    items.add(child.bytes == null ? new byte[0] : child.bytes);
                }
            }
            if (posRef[0] != end) throw new Exception("rlp list length mismatch");
            out.list = items;
            return out;
        }

        int lenOfLen = prefix - 0xf7;
        ensureAvail(data, pos + 1, lenOfLen);
        int listLen = readBigEndianInt(data, pos + 1, lenOfLen);
        ensureAvail(data, pos + 1 + lenOfLen, listLen);
        int end = pos + 1 + lenOfLen + listLen;

        out.isList = true;
        java.util.ArrayList<byte[]> items = new java.util.ArrayList<>();
        posRef[0] = pos + 1 + lenOfLen;

        while (posRef[0] < end) {
            RlpItem child = rlpDecodeItem(data, posRef);
            if (child.isList) items.add(new byte[0]);
            else items.add(child.bytes == null ? new byte[0] : child.bytes);
        }
        if (posRef[0] != end) throw new Exception("rlp list length mismatch");
        out.list = items;
        return out;
    }

    private static void ensureAvail(byte[] data, int start, int len) throws Exception {
        if (len < 0 || start < 0 || start + len > data.length) throw new Exception("rlp out of bounds");
    }

    private static int readBigEndianInt(byte[] data, int start, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) {
            v = (v << 8) | (data[start + i] & 0xff);
        }
        return v;
    }

    private static String formatGwei(java.math.BigInteger wei) {
        java.math.BigDecimal bd = new java.math.BigDecimal(wei);
        java.math.BigDecimal g = bd.divide(new java.math.BigDecimal("1000000000"), 9, java.math.RoundingMode.DOWN);
        return g.stripTrailingZeros().toPlainString() + " gwei";
    }

    private static String formatUnits(java.math.BigInteger value, int decimals) {
        java.math.BigDecimal bd = new java.math.BigDecimal(value);
        java.math.BigDecimal div = java.math.BigDecimal.TEN.pow(decimals);
        java.math.BigDecimal out = bd.divide(div, decimals, java.math.RoundingMode.DOWN);
        return out.stripTrailingZeros().toPlainString();
    }

    private static class Action {
        String kind;
        String recipient;
        String selector;
        String tokenSymbol;
        String humanAmount;
    }

    private static Action classifyAction(long chainId, String toAddr, java.math.BigInteger valueWei, byte[] dataBuf) {
        Action a = new Action();

        String dataHex = (dataBuf == null || dataBuf.length == 0) ? "" : bytesToHex(dataBuf);
        boolean hasData = dataHex != null && dataHex.length() > 0;

        if (!hasData) {
            a.kind = (valueWei.signum() > 0) ? "eth.transfer" : "eth-no-data";
            a.recipient = toAddr;
            return a;
        }

        // ERC20 transfer selector: a9059cbb (transfer(address,uint256))
        if (dataHex.length() == 8 + 64 + 64 && dataHex.startsWith("a9059cbb")) {
            String toArg = dataHex.substring(8, 8 + 64);
            String amtArg = dataHex.substring(8 + 64, 8 + 128);

            String recipient = "0x" + toArg.substring(24);
            java.math.BigInteger amountRaw = new java.math.BigInteger(amtArg, 16);

            a.kind = "erc20.transfer";
            a.recipient = recipient;
            a.tokenSymbol = "ERC20";
            a.humanAmount = formatUnits(amountRaw, 18);
            return a;
        }

        a.kind = "contract.call";
        a.recipient = toAddr;
        a.selector = (dataHex.length() >= 8) ? dataHex.substring(0, 8) : null;
        return a;
    }

    private static String buildHumanJson(long chainId, String toAddr, String valueEth, Action action) {
        String token = "ETH";
        String amount = valueEth;
        String to = toAddr;

        String summary;

        if ("erc20.transfer".equals(action.kind)) {
            token = (action.tokenSymbol != null ? action.tokenSymbol : "ERC20");
            amount = (action.humanAmount != null ? action.humanAmount : "0");
            to = action.recipient;
            summary = "Send " + amount + " " + token + " to " + (to == null ? "(unknown)" : to);
        } else if ("eth.transfer".equals(action.kind)) {
            to = action.recipient;
            summary = "Send " + valueEth + " ETH to " + (to == null ? "(unknown)" : to);
        } else if ("contract.call".equals(action.kind)) {
            summary = "Contract call to " + (toAddr == null ? "(unknown)" : toAddr)
                    + (action.selector != null ? (" (selector " + action.selector + ")") : "");
        } else {
            summary = "No ETH value and no calldata";
        }

        String toJson = (to == null ? "null" : ("\"" + escapeJson(to) + "\""));

        String detailsJson =
                "{"
                        + "\"kind\":\"" + escapeJson(action.kind) + "\""
                        + ",\"to\":" + toJson
                        + ",\"recipient\":" + toJson
                        + (action.selector != null ? (",\"selector\":\"" + escapeJson(action.selector) + "\"") : "")
                        + "}";

        String feesJson = "{}";

        String toJson = (to == null ? "null" : ("\"" + escapeJson(to) + "\""));


        return "{"
                + "\"fromAddress\":null,"
                + "\"toAddress\":" + toJson + ","
                + "\"recipient\":" + toJson + ","
                + "\"to\":" + toJson + ","
                + "\"token\":\"" + escapeJson(token) + "\","
                + "\"amount\":\"" + escapeJson(amount) + "\","
                + "\"details\":" + detailsJson + ","
                + "\"fees\":" + feesJson + ","
                + "\"summary\":\"" + escapeJson(summary) + "\""
                + "}";
    }

    // --------------------------
    // Static asset serving
    // --------------------------
    private Response serveAsset(String uri) throws Exception {
        String assetPath = uri.startsWith("/") ? uri.substring(1) : uri;
        if (assetPath.isEmpty()) assetPath = "index.html";

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
    // Basic helpers (readAll, JSON extract, HD derivation, CBOR origin, Bytewords, QR)
    // --------------------------
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        return out.toByteArray();
    }

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
