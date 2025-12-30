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
    if ("/api/sign".equals(uri) && method == Method.POST) {
        return handleApiSign(postData);
    }

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
        top.Add(8, (long) (parentFp & 0xffffffffL));
        top.Add(9, "AirGap - meta");

        byte[] cborBytes = top.EncodeToBytes();

// UR: crypto-hdkey + STANDARD bytewords (with CRC32) for wallet compatibility
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
            byte[] decoded;
String bwNorm = (bw == null ? "" : bw.trim().toLowerCase(Locale.ROOT));

// Heuristic: if it contains '-' (or any non [a-z]) it's almost certainly STANDARD
// Otherwise try MINIMAL first, then fall back to STANDARD.
try {
    if (bwNorm.contains("-") || bwNorm.matches(".*[^a-z].*")) {
        decoded = bytewordsStandardDecodeWithCrc(bwNorm);
    } else {
        decoded = bytewordsMinimalDecodeWithCrc(bwNorm);
    }
} catch (Exception first) {
    // Fallback: try the other format
    try {
        decoded = bytewordsStandardDecodeWithCrc(bwNorm);
    } catch (Exception second) {
        throw first; // keep original error for log clarity
    }
}
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
// /api/sign  (Sign transaction)
// --------------------------
private Response handleApiSign(String body) throws Exception {
    String privateKeyHex = jsonGetString(body, "privateKeyHex", "").trim();
    if (privateKeyHex.isEmpty()) {
        return jsonError(Response.Status.BAD_REQUEST, "Missing privateKeyHex");
    }
    if (privateKeyHex.startsWith("0x") || privateKeyHex.startsWith("0X")) {
        privateKeyHex = privateKeyHex.substring(2);
    }
    if (privateKeyHex.length() != 64) {
        return jsonError(Response.Status.BAD_REQUEST, "privateKeyHex must be 32 bytes (64 hex chars)");
    }

    if (lastUrType == null || lastUrCbor == null || !"eth-sign-request".equals(lastUrType)) {
        return jsonError(Response.Status.BAD_REQUEST, "No eth-sign-request present; scan request QR first");
    }

    com.upokecenter.cbor.CBORObject req = com.upokecenter.cbor.CBORObject.DecodeFromBytes(lastUrCbor);
    if (req == null || req.getType() != com.upokecenter.cbor.CBORType.Map) {
        return jsonError(Response.Status.BAD_REQUEST, "Invalid sign request CBOR");
    }

    byte[] requestId = getBytesByKey(req, 1);
    byte[] signData = getBytesByKey(req, 2);
    Integer dataType = getIntByKey(req, 3);
    Long chainId = getLongByKey(req, 4);

    if (signData == null || signData.length == 0) signData = findLikelySignData(req);
    if (dataType == null) dataType = findLikelyDataType(req);

    if (signData == null || signData.length == 0) {
        return jsonError(Response.Status.BAD_REQUEST, "Could not locate signData in request");
    }
    if (dataType == null) {
        return jsonError(Response.Status.BAD_REQUEST, "Could not locate dataType in request");
    }

    // Hash the payload bytes (same as server.js: keccak256 of signData bytes)
// Keccak256(signData)
byte[] hash = keccak256(signData);

// Build ECKey from private key (bitcoinj uses secp256k1)
java.math.BigInteger priv = new java.math.BigInteger(1, hexToBytes(privateKeyHex));
org.bitcoinj.core.ECKey ecKey = org.bitcoinj.core.ECKey.fromPrivate(priv, false);

// Sign the 32-byte hash
org.bitcoinj.core.ECKey.ECDSASignature sig = ecKey.sign(org.bitcoinj.core.Sha256Hash.wrap(hash));

// Ensure low-S form (Ethereum requires this)
sig = sig.toCanonicalised();

// r and s (32 bytes each)
byte[] r = pad32(sig.r.toByteArray());
byte[] s = pad32(sig.s.toByteArray());

// recovery id (needed to compute v)
int recId = -1;
for (int i = 0; i < 4; i++) {
    org.bitcoinj.core.ECKey k = org.bitcoinj.core.ECKey.recoverFromSignature(
            i,
            sig,
            org.bitcoinj.core.Sha256Hash.wrap(hash),
            false
    );
    if (k != null && k.getPubKeyPoint().equals(ecKey.getPubKeyPoint())) {
        recId = i;
        break;
    }
}
if (recId == -1) {
    return jsonError(Response.Status.INTERNAL_ERROR, "Could not construct recoverable signature");
}

// Ethereum v is 27/28 for unprotected signatures (we return both forms in CBOR)
int v = recId + 27;
byte vNorm = (byte) (recId & 0x01); // 0/1

byte[] sig65 = new byte[65];
System.arraycopy(r, 0, sig65, 0, 32);
System.arraycopy(s, 0, sig65, 32, 32);
sig65[64] = vNorm;


    // CBOR response
    com.upokecenter.cbor.CBORObject resp = com.upokecenter.cbor.CBORObject.NewMap();
    if (requestId != null) resp.Add(1, requestId);
    resp.Add(2, sig65);
    resp.Add(3, (int) (v & 0xff));
    resp.Add(4, r);
    resp.Add(5, s);
    if (chainId != null) resp.Add(6, chainId);

    byte[] respCbor = resp.EncodeToBytes();

    // Encode as UR
    String urBody = bytewordsMinimalWithCrc(respCbor);
    String urText = ("ur:eth-signature/" + urBody).toUpperCase(Locale.ROOT);

    String qrDataUrl = makeQrDataUrl(urText, 8);

// vNorm is 0/1 (parity). r and s are 32-byte arrays.
int vParity = (vNorm & 0xff);

String rHex = bytesToHex(r);
String sHex = bytesToHex(s);

// Optional: Ethereum address from public key (safe to include; UI will use it if present)
String address = null;
try {
    // ecKey is the bitcoinj ECKey you created from the private key
    byte[] pubUncompressed = ecKey.getPubKeyPoint().getEncoded(false); // 65 bytes, starts with 0x04
    byte[] pub64 = Arrays.copyOfRange(pubUncompressed, 1, pubUncompressed.length); // drop 0x04
    byte[] h = keccak256(pub64);
    byte[] addr20 = Arrays.copyOfRange(h, h.length - 20, h.length);
    address = "0x" + bytesToHex(addr20);
} catch (Exception ignored) {}

// IMPORTANT: sign.html expects these names:
String json =
        "{"
                + "\"ok\":true,"
                + "\"urSignatureUpper\":\"" + escapeJson(urText) + "\","
                + "\"r\":\"" + rHex + "\","
                + "\"s\":\"" + sHex + "\","
                + "\"vParity\":" + vParity + ","
                + (address != null ? ("\"address\":\"" + escapeJson(address) + "\",") : "")
                + "\"urType\":\"ETH-SIGNATURE\""
                + "}";

return jsonOk(json);
}

// --- helpers for /api/sign ---
private static byte[] hexToBytes(String hex) {
    int len = hex.length();
    byte[] out = new byte[len / 2];
    for (int i = 0; i < out.length; i++) {
        int hi = Character.digit(hex.charAt(i * 2), 16);
        int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
        out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
}

private static byte[] keccak256(byte[] input) {
    // bitcoinj does not include keccak; use BouncyCastle digest already available in Android toolchain
    org.bouncycastle.jcajce.provider.digest.Keccak.Digest256 d =
            new org.bouncycastle.jcajce.provider.digest.Keccak.Digest256();
    d.update(input, 0, input.length);
    return d.digest();
}

private static byte[] pad32(byte[] b) {
    if (b == null) return new byte[32];
    if (b.length == 32) return b;
    byte[] out = new byte[32];
    if (b.length > 32) {
        System.arraycopy(b, b.length - 32, out, 0, 32);
    } else {
        System.arraycopy(b, 0, out, 32 - b.length, b.length);
    }
    return out;
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

    // ---- ERC20 token metadata (symbol/decimals) ----
    private static class TokenInfo {
        final String symbol;
        final int decimals;
        TokenInfo(String symbol, int decimals) {
            this.symbol = symbol;
            this.decimals = decimals;
        }
    }

    // Key format: "<chainId>:<contractAddressLowercase>"
    // Note: keep addresses lowercase to avoid mismatches.
    private static final java.util.Map<String, TokenInfo> TOKEN_DECIMALS = new java.util.HashMap<>();
    static {
        // =========================
        // Ethereum Mainnet (1)
        // =========================
        // Stablecoins
        TOKEN_DECIMALS.put("1:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", new TokenInfo("USDC", 6));
        TOKEN_DECIMALS.put("1:0xdac17f958d2ee523a2206206994597c13d831ec7", new TokenInfo("USDT", 6));
        TOKEN_DECIMALS.put("1:0x6b175474e89094c44da98b954eedeac495271d0f", new TokenInfo("DAI", 18));
        TOKEN_DECIMALS.put("1:0xdc035d45d973e3ec169d2276ddab16f1e407384f", new TokenInfo("USDS", 18));

        // ETH derivatives
        TOKEN_DECIMALS.put("1:0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", new TokenInfo("WETH", 18));
        TOKEN_DECIMALS.put("1:0xae7ab96520de3a18e5e111b5eaab095312d7fe84", new TokenInfo("stETH", 18));
        TOKEN_DECIMALS.put("1:0x7f39c581f595b53c5cb19bd0b3f8da6c935e2ca0", new TokenInfo("wstETH", 18));
        TOKEN_DECIMALS.put("1:0xcd5fe23c85820f7b72d0926fc9b05b43e359b7ee", new TokenInfo("weETH", 18));

        // BTC derivatives
        TOKEN_DECIMALS.put("1:0x2260fac5e5542a773aa44fbcfedf7c193bc2c599", new TokenInfo("WBTC", 8));
        TOKEN_DECIMALS.put("1:0x66eff5221ca926636224650fd3b9c497ff828f7d", new TokenInfo("multiBTC", 8));
        TOKEN_DECIMALS.put("1:0x9be89d2a4cd102d8fecc6bf9da793be995c22541", new TokenInfo("cbBTC", 8));

        // Other wrapped assets
        TOKEN_DECIMALS.put("1:0xb8c77482e45f1f44de1745f52c74426c631bdd52", new TokenInfo("BNB", 18));
        TOKEN_DECIMALS.put("1:0xa2e3356610840701bdf5611a53974510ae27e2e1", new TokenInfo("WBETH", 18));

        // DeFi
        TOKEN_DECIMALS.put("1:0x514910771af9ca656af840dff83e8264ecf986ca", new TokenInfo("LINK", 18));
        TOKEN_DECIMALS.put("1:0x1f9840a85d5af5bf1d1762f925bdaddc4201f984", new TokenInfo("UNI", 18));
        TOKEN_DECIMALS.put("1:0x7fc66500c84a76ad7e9c93437bfc5ac33e2ddae9", new TokenInfo("AAVE", 18));

        // Memes
        TOKEN_DECIMALS.put("1:0x95ad61b0a150d79219dcf64e1e6cc01f0b64c4ce", new TokenInfo("SHIB", 18));

        // Commodities
        TOKEN_DECIMALS.put("1:0x68749665ff8d2d112fa859aa293f07a622782f38", new TokenInfo("XAUT", 6));
        TOKEN_DECIMALS.put("1:0x45804880de22913dafe09f4980848ece6ecbaf78", new TokenInfo("PAXG", 18));

        // =========================
        // Base (8453)
        // =========================
        TOKEN_DECIMALS.put("8453:0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", new TokenInfo("USDC", 6));
        TOKEN_DECIMALS.put("8453:0x4200000000000000000000000000000000000006", new TokenInfo("WETH", 18));
        TOKEN_DECIMALS.put("8453:0xc1cba3fcea344f92d9239c08c0568f6f2f0ee452", new TokenInfo("wstETH", 18));
        TOKEN_DECIMALS.put("8453:0x820c137fa70c8691f0e44dc420a5e53c168921dc", new TokenInfo("USDS", 18));
        TOKEN_DECIMALS.put("8453:0xcbb7c0000ab88b473b1f5afd9ef808440eed33bf", new TokenInfo("cbBTC", 8));
        TOKEN_DECIMALS.put("8453:0x50c5725949a6f0c72e6c4a641f24049a917db0cb", new TokenInfo("DAI", 18));

        // =========================
        // Arbitrum One (42161)
        // =========================
        TOKEN_DECIMALS.put("42161:0xaf88d065e77c8cc2239327c5edb3a432268e5831", new TokenInfo("USDC", 6));
        TOKEN_DECIMALS.put("42161:0x82af49447d8a07e3bd95bd0d56f35241523fbab1", new TokenInfo("WETH", 18));
        TOKEN_DECIMALS.put("42161:0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f", new TokenInfo("WBTC", 8));
        TOKEN_DECIMALS.put("42161:0x5979d7b546e38e414f7e9822514be443a4800529", new TokenInfo("wstETH", 18));
        TOKEN_DECIMALS.put("42161:0x6491c05a82219b8d1479057361ff1654749b876b", new TokenInfo("USDS", 18));
        TOKEN_DECIMALS.put("42161:0xcbb7c0000ab88b473b1f5afd9ef808440eed33bf", new TokenInfo("cbBTC", 8));
        TOKEN_DECIMALS.put("42161:0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", new TokenInfo("USDT0", 6));
        TOKEN_DECIMALS.put("42161:0xfa7f8980b0f1e64a2062791cc3b0871572f1f7f0", new TokenInfo("UNI", 18));
        TOKEN_DECIMALS.put("42161:0x912ce59144191c1204e64559fe8253a0e49e6548", new TokenInfo("ARB", 18));
        TOKEN_DECIMALS.put("42161:0xf97f4df75117a78c1a5a0dbb814af92458539fb4", new TokenInfo("LINK", 18));
    }

    private static TokenInfo lookupToken(long chainId, String contractAddr0x) {
        if (contractAddr0x == null) return null;
        String key = chainId + ":" + contractAddr0x.toLowerCase();
        return TOKEN_DECIMALS.get(key);
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

            TokenInfo ti = lookupToken(chainId, toAddr);
            a.tokenSymbol = (ti != null ? ti.symbol : "ERC20");
            a.humanAmount = formatUnits(amountRaw, (ti != null ? ti.decimals : 18));

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
        summary = "Review carefully on your device.";
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
        inner.Add(2, (long) (masterFpU32 & 0xffffffffL));
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

    // STANDARD bytewords: 4-letter words separated by '-' + CRC32 (4 bytes, big-endian)
private static String bytewordsStandardWithCrc(byte[] data) {
    byte[] withCrc = appendCrc32BigEndian(data);
    StringBuilder sb = new StringBuilder(withCrc.length * 5);
    for (int i = 0; i < withCrc.length; i++) {
        int u = withCrc[i] & 0xff;
        if (i > 0) sb.append('-');
        sb.append(BYTEWORDS[u]); // full 4-letter word
    }
    return sb.toString();
}

private static byte[] bytewordsStandardDecodeWithCrc(String text) throws Exception {
    // Accept '-' separators, spaces, etc.
    String s = (text == null ? "" : text.trim().toLowerCase(Locale.ROOT));
    if (s.isEmpty()) throw new Exception("empty bytewords");

    // Split on anything that's not a-z
    String[] parts = s.split("[^a-z]+");
    if (parts.length == 0) throw new Exception("invalid bytewords");

    // Build word -> byte lookup once per decode (256 only, cheap)
    java.util.Map<String, Integer> wordToByte = new java.util.HashMap<>(512);
    for (int i = 0; i < 256; i++) wordToByte.put(BYTEWORDS[i], i);

    byte[] out = new byte[parts.length];
    int n = 0;
    for (String w : parts) {
        if (w == null || w.isEmpty()) continue;
        Integer v = wordToByte.get(w);
        if (v == null) throw new Exception("invalid bytewords word: " + w);
        out[n++] = (byte)(v & 0xff);
    }
    if (n < 4) throw new Exception("missing crc32");

    byte[] full = java.util.Arrays.copyOf(out, n);

    byte[] payload = java.util.Arrays.copyOfRange(full, 0, full.length - 4);
    byte[] crc = java.util.Arrays.copyOfRange(full, full.length - 4, full.length);

    java.util.zip.CRC32 c = new java.util.zip.CRC32();
    c.update(payload);
    long want = c.getValue();
    long got = ((crc[0] & 0xffL) << 24) | ((crc[1] & 0xffL) << 16) | ((crc[2] & 0xffL) << 8) | (crc[3] & 0xffL);

    if ((want & 0xffffffffL) != (got & 0xffffffffL)) throw new Exception("crc32 mismatch");
    return payload;
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
