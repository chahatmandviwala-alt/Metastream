// android-webviewer/node-server/server-android.js
//
// Android-safe server implementation for the same API routes used by your tabs.
// This version removes the native bip32/tiny-secp256k1 dependency by using @scure/*.
//
// NOTE: This file does NOT change Electron behavior. Electron can keep using your existing server.js.

const express = require("express");
const path = require("path");

// --- Tool 1 deps (Android-safe) ---
const crypto = require("crypto");
const QRCode = require("qrcode");
const cbor = require("cbor");
const { UR, UREncoder } = require("@ngraveio/bc-ur");

// Replace bip39 + bip32 + tiny-secp256k1 with @scure/*
const { mnemonicToSeedSync, validateMnemonic } = require("@scure/bip39");
const { wordlist } = require("@scure/bip39/wordlists/english");
const { HDKey } = require("@scure/bip32");

// --- Tool 2 deps (same as your server.js) ---
const rlp = require("rlp");
const { URRegistryDecoder, RegistryTypes, ETHSignature } = require("@onekeyfe/hd-air-gap-sdk");
const { EthSignRequest } = require("@keystonehq/bc-ur-registry-eth");
const { Wallet, keccak256 } = require("ethers");

const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json({ limit: "2mb" }));

// Serve the same public/ folder (later we will point WebView to http://127.0.0.1:PORT/)
app.use(express.static(path.join(__dirname, "..", "..", "public")));

// ------------------------
// Tool 1: /api/gen
// ------------------------
function hash160(buf) {
  const sha = crypto.createHash("sha256").update(buf).digest();
  return crypto.createHash("ripemd160").update(sha).digest();
}
function fingerprintOfPubkey(pubKeyBytes) {
  const h160 = hash160(Buffer.from(pubKeyBytes));
  return h160.readUInt32BE(0) >>> 0;
}
function parsePath(pathStr) {
  const s = String(pathStr || "").trim();
  if (!/^m(\/\d+'?)*$/.test(s)) throw new Error("Invalid derivation path.");
  return s;
}
function parentPathOf(pathStr) {
  const parts = pathStr.split("/");
  return parts.length <= 2 ? "m" : parts.slice(0, -1).join("/");
}
function u32FromHexBE(hex8) {
  if (!hex8) return null;
  const b = Buffer.from(hex8, "hex");
  if (b.length !== 4) throw new Error("Override fingerprint must be 8 hex chars");
  return b.readUInt32BE(0) >>> 0;
}

// Build origin/tag(304) payload with compact array + masterFP (uint32)
function buildOriginTag304(pathStr, masterFpU32) {
  // Keep same structure as your server.js
  const segments = pathStr.split("/").slice(1); // remove 'm'
  const arr = [];
  for (const seg of segments) {
    const hardened = seg.endsWith("'");
    const idx = parseInt(hardened ? seg.slice(0, -1) : seg, 10);
    if (!Number.isFinite(idx)) throw new Error("Invalid index in path.");
    arr.push(idx, !!hardened);
  }

  const inner = new Map();
  inner.set(1, arr);
  inner.set(2, masterFpU32 >>> 0);
  return new cbor.Tagged(304, inner);
}

app.post("/api/gen", async (req, res) => {
  try {
    const {
      mnemonic,
      passphrase = "",
      path: derivationPath = `m/44'/60'/0'`,
      qrScale = 8,
      forceMasterFpHex = "",
      forceParentFpHex = "",
      parentFrom = "auto",
    } = req.body || {};

    const words = String(mnemonic || "").trim().toLowerCase().replace(/\s+/g, " ");
    if (!validateMnemonic(words, wordlist)) {
      return res.status(400).json({ error: "Invalid BIP39 mnemonic." });
    }

    // @scure/bip39 seed
    const seed = mnemonicToSeedSync(words, passphrase);

    // @scure/bip32 master
    const master = HDKey.fromMasterSeed(seed);

    // validate + derive
    parsePath(derivationPath);
    const node = master.derive(derivationPath);

    let parentPath = parentPathOf(derivationPath);
    if (parentFrom === "m44h60h") parentPath = `m/44'/60'`;
    const parent = parentPath === "m" ? master : master.derive(parentPath);

    // fingerprints from pubkey
    let masterFpU32 = fingerprintOfPubkey(master.publicKey);
    let parentFpU32 = fingerprintOfPubkey(parent.publicKey);

    const mOverride = u32FromHexBE(forceMasterFpHex);
    const pOverride = u32FromHexBE(forceParentFpHex);
    if (mOverride !== null) masterFpU32 = mOverride;
    if (pOverride !== null) parentFpU32 = pOverride;

    // Build crypto-hdkey payload (same keys as your server.js)
    const originTagged = buildOriginTag304(derivationPath, masterFpU32);
    const top = new Map();
    top.set(3, Buffer.from(node.publicKey));
    top.set(4, Buffer.from(node.chainCode));
    top.set(6, originTagged);
    top.set(8, parentFpU32 >>> 0);
    top.set(9, "AirGap - meta");

    const cborBytes = cbor.encode(top);
    const ur = new UR(cborBytes, "crypto-hdkey");
    const urText = new UREncoder(ur, 400).nextPart().toUpperCase();

    const scale = Math.max(3, Math.min(12, Number(qrScale) || 8));
    const qrDataUrl = await QRCode.toDataURL(urText, {
      errorCorrectionLevel: "L",
      margin: 1,
      scale,
    });

    res.json({
      ur: urText,
      path: derivationPath,
      parentPath,
      masterFpHex: (masterFpU32 >>> 0).toString(16).padStart(8, "0"),
      parentFpHex: (parentFpU32 >>> 0).toString(16).padStart(8, "0"),
      qrDataUrl,
    });
  } catch (e) {
    res.status(500).json({ error: e?.message || String(e) });
  }
});

// ------------------------
// Tool 2: UR collector + signer
// Routes: /api/ur/reset, /api/ur/part, /api/ur/decoded, /api/sign
// ------------------------

// In-memory state
let decoder = null;
let lastUR = null; // { type, cbor }

app.get("/health", (_req, res) => res.send("ok"));

app.post("/api/ur/reset", (_req, res) => {
  decoder = new URRegistryDecoder();
  lastUR = null;
  res.json({ ok: true });
});

app.post("/api/ur/part", (req, res) => {
  const { part } = req.body || {};
  if (!part || typeof part !== "string") {
    return res.status(400).json({ error: "Missing { part } string" });
  }
  if (!decoder) decoder = new URRegistryDecoder();

  const p = part.trim().toLowerCase();
  decoder.receivePart(p);

  if (decoder.isError()) {
    const err = decoder.resultError && decoder.resultError();
    return res.json({ status: "error", error: String(err || "decode error") });
  }
  if (!decoder.isComplete()) {
    return res.json({ status: "collecting" });
  }

  const ur = decoder.resultUR();
  lastUR = ur;

  const type = ur.type;
  const typeExpected = RegistryTypes.ETH_SIGN_REQUEST.getType();

  res.json({
    status: "complete",
    type,
    cborHex: Buffer.from(ur.cbor).toString("hex"),
    ok: type === typeExpected,
  });
});

app.get("/api/ur/decoded", (_req, res) => {
  if (!lastUR) return res.status(400).json({ error: "No assembled UR yet." });
  res.json({
    type: lastUR.type,
    cborHex: Buffer.from(lastUR.cbor).toString("hex"),
  });
});

app.post("/api/sign", (req, res) => {
  try {
    const { privateKeyHex } = req.body || {};
    if (!privateKeyHex || !/^[0-9a-fA-F]{64}$/.test(privateKeyHex)) {
      return res.status(400).json({ error: "Provide { privateKeyHex } as 64 hex chars (no 0x)." });
    }
    if (!lastUR) {
      return res.status(400).json({ error: "No assembled UR yet. Scan the animated QR first." });
    }
    if (lastUR.type !== RegistryTypes.ETH_SIGN_REQUEST.getType()) {
      return res.status(400).json({ error: `Last UR type was ${lastUR.type}, expected eth-sign-request.` });
    }

    const reqObj = EthSignRequest.fromCBOR(lastUR.cbor);
    const dataType = reqObj.getDataType();
    if (dataType !== 1 && dataType !== 4) {
      return res.status(400).json({ error: `This demo signs tx types only (dataType 1 or 4). Got ${dataType}.` });
    }

    const signBytes = Buffer.from(reqObj.getSignData());
    const msgHash = keccak256("0x" + signBytes.toString("hex"));

    const wallet = new Wallet("0x" + privateKeyHex);
    const sig = wallet.signingKey.sign(msgHash);
    const yParity = (sig.yParity !== undefined) ? sig.yParity : (sig.v % 2);

    const rBuf = Buffer.from(sig.r.slice(2), "hex");
    const sBuf = Buffer.from(sig.s.slice(2), "hex");
    const vBuf = Buffer.from([yParity]);
    const signature65 = Buffer.concat([rBuf, sBuf, vBuf]);

    const requestIdBuf = reqObj.getRequestId() ? Buffer.from(reqObj.getRequestId()) : undefined;
    const ethSig = new ETHSignature(signature65, requestIdBuf);

    const encoder = ethSig.toUREncoder(200);
    const urSignature = encoder.nextPart();

    res.json({
      ok: true,
      urSignatureUpper: urSignature.toUpperCase(),
      urSignature,
      r: sig.r,
      s: sig.s,
      vParity: yParity,
    });
  } catch (e) {
    console.error("SIGN ERROR:", e);
    res.status(500).json({ error: String(e?.message || e) });
  }
});

app.post("/api/shutdown", (_req, res) => {
  res.json({ ok: true });
  process.exit(0);
});

app.listen(PORT, "127.0.0.1", () => {
  console.log(`[android-server] listening on http://127.0.0.1:${PORT}`);
});
