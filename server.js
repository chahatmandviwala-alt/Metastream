// server.js — merged server for:
// 1) Connect Wallet (AirGap-style UR:CRYPTO-HDKEY generator)
// 2) Sign Transaction (animated UR collector + ETH signer)

const express = require('express');
const path = require('path');

// --- Tool 1 deps ---
const crypto = require('crypto');
const QRCode = require('qrcode');
const ecc = require('tiny-secp256k1');
const { BIP32Factory } = require('bip32');
const bip32 = BIP32Factory(ecc);
const bip39 = require('bip39');
const cbor = require('cbor');
const { UR, UREncoder } = require('@ngraveio/bc-ur');

// --- Tool 2 deps ---
const rlp = require('rlp');
const { URRegistryDecoder, RegistryTypes, ETHSignature } = require('@onekeyfe/hd-air-gap-sdk');
const { EthSignRequest } = require('@keystonehq/bc-ur-registry-eth');
const { Wallet, keccak256 } = require('ethers');

const app = express();
const PORT = process.env.PORT || 3000;

app.use(express.json({ limit: '2mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// ------------------------
// Tool 1: /api/gen
// ------------------------
function hash160(buf) {
  const sha = crypto.createHash('sha256').update(buf).digest();
  return crypto.createHash('ripemd160').update(sha).digest();
}
function fingerprintOf(node) {
  const h160 = hash160(Buffer.from(node.publicKey));
  return h160.readUInt32BE(0) >>> 0; // uint32
}
function parsePath(pathStr) {
  const s = String(pathStr || '').trim();
  if (!/^m(\/\d+'?)*$/.test(s)) throw new Error('Invalid derivation path.');
  return s.split('/').slice(1).map(seg => {
    const hardened = seg.endsWith("'");
    const idx = parseInt(hardened ? seg.slice(0, -1) : seg, 10);
    if (!Number.isFinite(idx)) throw new Error('Invalid index in path.');
    return { index: idx, hardened };
  });
}
function parentPathOf(pathStr) {
  const parts = pathStr.split('/');
  return parts.length <= 2 ? 'm' : parts.slice(0, -1).join('/');
}
function u32FromHexBE(hex8) {
  if (!hex8) return null;
  const b = Buffer.from(hex8, 'hex');
  if (b.length !== 4) throw new Error('Override fingerprint must be 8 hex chars');
  return b.readUInt32BE(0) >>> 0;
}

// Build origin/tag(304) payload with compact array + masterFP (uint32)
function buildOriginTag304(pathStr, masterFpU32) {
  const parts = parsePath(pathStr);
  const arr = [];
  for (const p of parts) arr.push(p.index, !!p.hardened); // compact: index, boolean
  const inner = new Map();
  inner.set(1, arr);
  inner.set(2, masterFpU32 >>> 0);
  return new cbor.Tagged(304, inner);
}

app.post('/api/gen', async (req, res) => {
  try {
    const {
      mnemonic,
      passphrase = '',
      path: derivationPath = `m/44'/60'/0'`,
      qrScale = 8,
      // Optional overrides for exact fingerprints
      forceMasterFpHex = '',
      forceParentFpHex = '',
      parentFrom = 'auto',
    } = req.body || {};

    const words = String(mnemonic || '').trim().toLowerCase().replace(/\s+/g, ' ');
    if (!bip39.validateMnemonic(words)) {
      return res.status(400).json({ error: 'Invalid BIP39 mnemonic.' });
    }

    const seed = await bip39.mnemonicToSeed(words, passphrase);
    const master = bip32.fromSeed(seed);

    parsePath(derivationPath); // validates
    const node = master.derivePath(derivationPath);

    let parentPath = parentPathOf(derivationPath);
    if (parentFrom === 'm44h60h') parentPath = `m/44'/60'`;
    const parent = parentPath === 'm' ? master : master.derivePath(parentPath);

    let masterFpU32 = fingerprintOf(master);
    let parentFpU32 = fingerprintOf(parent);
    const mOverride = u32FromHexBE(forceMasterFpHex);
    const pOverride = u32FromHexBE(forceParentFpHex);
    if (mOverride !== null) masterFpU32 = mOverride;
    if (pOverride !== null) parentFpU32 = pOverride;

    const originTagged = buildOriginTag304(derivationPath, masterFpU32);
    const top = new Map();
    top.set(3, Buffer.from(node.publicKey));
    top.set(4, Buffer.from(node.chainCode));
    top.set(6, originTagged);
    top.set(8, parentFpU32 >>> 0);
    top.set(9, 'AirGap - meta');

    const cborBytes = cbor.encode(top);
    const ur = new UR(cborBytes, 'crypto-hdkey');
    const urText = new UREncoder(ur, 400).nextPart().toUpperCase();

    const scale = Math.max(3, Math.min(12, Number(qrScale) || 8));
    const qrDataUrl = await QRCode.toDataURL(urText, { errorCorrectionLevel: 'L', margin: 1, scale });

    res.json({
      ur: urText,
      path: derivationPath,
      parentPath,
      masterFpHex: (masterFpU32 >>> 0).toString(16).padStart(8, '0'),
      parentFpHex: (parentFpU32 >>> 0).toString(16).padStart(8, '0'),
      qrDataUrl,
    });
  } catch (e) {
    res.status(500).json({ error: e?.message || String(e) });
  }
});

// ------------------------
// Tool 2: Animated UR collector + signer
// Routes: /api/ur/reset, /api/ur/part, /api/ur/decoded, /api/sign
// ------------------------

// In-memory state (OK for local dev)
let decoder = null;
let lastUR = null; // { type, cbor }

app.get('/health', (_req, res) => res.send('ok'));

app.post('/api/ur/reset', (_req, res) => {
  decoder = new URRegistryDecoder();
  lastUR = null;
  res.json({ ok: true });
});

app.post('/api/ur/part', (req, res) => {
  const { part } = req.body || {};
  if (!part || typeof part !== 'string') {
    return res.status(400).json({ error: 'Missing { part } string' });
  }
  if (!decoder) decoder = new URRegistryDecoder();

  const p = part.trim().toLowerCase();
  decoder.receivePart(p);

  if (decoder.isError()) {
    const err = decoder.resultError && decoder.resultError();
    return res.json({ status: 'error', error: String(err || 'decode error') });
  }
  if (!decoder.isComplete()) {
    return res.json({ status: 'collecting' });
  }

  const ur = decoder.resultUR();
  lastUR = ur;

  const type = ur.type;
  const typeExpected = RegistryTypes.ETH_SIGN_REQUEST.getType();

  res.json({
    status: 'complete',
    type,
    cborHex: Buffer.from(ur.cbor).toString('hex'),
    ok: type === typeExpected,
  });
});

// ---- helpers to safely read RLP fields and format units ----
const readHex = (buf) => Buffer.from(buf ?? []).toString('hex');
const readBig = (buf) => { const h = readHex(buf); return h ? BigInt('0x' + h) : 0n; };
const readNum = (buf) => { const h = readHex(buf); return h ? parseInt(h, 16) : 0; };
const padLeft = (s, len) => (s.length >= len ? s : '0'.repeat(len - s.length) + s);
const formatUnits = (valueBigInt, decimals) => {
  const s = valueBigInt.toString();
  if (decimals === 0) return s;
  const i = s.length > decimals ? s.length - decimals : 0;
  const whole = i ? s.slice(0, i) : '0';
  const frac = padLeft(i ? s.slice(i) : s, decimals).replace(/0+$/, '') || '0';
  return `${whole}.${frac}`;
};

const TOKEN_DECIMALS = {
  '8453:0x833589fcd6edb6e08f4c7c32d4f71b54bda02913': { symbol: 'USDC', decimals: 6 },
  '1:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48': { symbol: 'USDC', decimals: 6 },
  '1:0x6b175474e89094c44da98b954eedeac495271d0f': { symbol: 'DAI', decimals: 18 },
};

const ensure0x = (h) => (h && h.startsWith('0x') ? h : '0x' + (h || ''));
const formatGwei = (weiBig) => {
  const gwei = Number((weiBig / 1000000000n).toString());
  return gwei.toLocaleString(undefined, { maximumFractionDigits: 6 }) + ' gwei';
};

// NOTE: The original server2.js contained a "naive" checksum helper.
// To avoid any environment-specific crypto issues, we keep addresses lowercase here.
const normalizeAddr = (addr) => (addr || '').toLowerCase();

app.get('/api/ur/decoded', (_req, res) => {
  try {
    if (!lastUR) return res.status(400).json({ error: 'No assembled UR yet. Scan the animated QR first.' });
    if (lastUR.type !== RegistryTypes.ETH_SIGN_REQUEST.getType()) {
      return res.status(400).json({ error: `Last UR type was ${lastUR.type}, expected eth-sign-request.` });
    }

    const reqObj = EthSignRequest.fromCBOR(lastUR.cbor);
    const base = {
      urType: 'eth-sign-request',
      dataType: reqObj.getDataType(),
      derivationPath: reqObj.getDerivationPath(),
      chainId: reqObj.getChainId() ?? null,
      requestIdHex: reqObj.getRequestId() ? Buffer.from(reqObj.getRequestId()).toString('hex') : null,
    };

    let fromAddress = null;

    // EIP-1559 (typed tx)
    if (base.dataType === 4) {
      const signBytes = Buffer.from(reqObj.getSignData());
      if (signBytes[0] !== 0x02) {
        return res.json({ ...base, note: 'Expected type 0x02 (EIP-1559) but first byte differs.' });
      }

      const decoded = rlp.decode(signBytes.slice(1));
      const [chainIdBuf, nonceBuf, maxPriFeeBuf, maxFeeBuf, gasLimitBuf, toBuf, valueBuf, dataBuf] = decoded;

      const chainId = readNum(chainIdBuf) || base.chainId || 0;
      const toHexStr = readHex(toBuf);
      const toAddr = toHexStr ? ensure0x(toHexStr) : '0x';
      const valueWei = readBig(valueBuf);
      const dataHex = readHex(dataBuf);
      const selector = dataHex.slice(0, 8);

      let action;
      let tokenMeta = null;

      if (dataHex && selector === 'a9059cbb' && dataHex.length === 8 + 64 + 64) {
        const toArg = dataHex.slice(8, 8 + 64);
        const amtArg = dataHex.slice(8 + 64, 8 + 128);
        const recipient = ensure0x(toArg.slice(24));
        const amountRaw = BigInt('0x' + amtArg);

        const key = `${chainId}:${normalizeAddr(toAddr)}`;
        const meta = TOKEN_DECIMALS[key] || { symbol: 'ERC20', decimals: 18 };
        tokenMeta = meta;

        action = {
          kind: 'erc20.transfer',
          tokenContract: toAddr,
          tokenSymbol: meta.symbol,
          tokenDecimals: meta.decimals,
          recipient,
          amountRaw: amountRaw.toString(),
          humanAmount: formatUnits(amountRaw, meta.decimals),
        };
      } else if (dataHex) {
        action = {
          kind: 'contract.call',
          to: toAddr,
          selector: '0x' + selector,
          dataLengthBytes: dataHex.length / 2,
        };
      } else if (valueWei > 0n) {
        action = {
          kind: 'eth.transfer',
          recipient: toAddr,
          valueWei: valueWei.toString(),
          valueEth: formatUnits(valueWei, 18),
        };
      } else {
        action = { kind: 'no-value-no-data', to: toAddr };
      }

      const tx = {
        type: 'eip-1559',
        chainId,
        nonce: readNum(nonceBuf),
        maxPriorityFeePerGasWei: readBig(maxPriFeeBuf).toString(),
        maxPriorityFeePerGas: formatGwei(readBig(maxPriFeeBuf)),
        maxFeePerGasWei: readBig(maxFeeBuf).toString(),
        maxFeePerGas: formatGwei(readBig(maxFeeBuf)),
        gasLimit: readNum(gasLimitBuf),
        to: toAddr,
        valueWei: valueWei.toString(),
        valueEth: formatUnits(valueWei, 18),
        action,
      };

      const human = {
        fromAddress,
        toAddress: action.recipient || tx.to,
        token: action.kind === 'erc20.transfer' ? (tokenMeta?.symbol || 'ERC20') : 'ETH',
        amount: action.kind === 'erc20.transfer' ? action.humanAmount : tx.valueEth,
        details: action,
        fees: {
          maxPriorityFeePerGas: tx.maxPriorityFeePerGas,
          maxFeePerGas: tx.maxFeePerGas,
          gasLimit: tx.gasLimit,
        },
        summary:
          action.kind === 'erc20.transfer'
            ? `Send ${action.humanAmount} ${tokenMeta?.symbol || 'ERC20'} to ${action.recipient}`
            : action.kind === 'eth.transfer'
              ? `Send ${tx.valueEth} ETH to ${action.recipient}`
              : action.kind === 'contract.call'
                ? `Contract call to ${action.to} (selector ${action.selector})`
                : 'No ETH value and no calldata',
      };

      return res.json({ ...base, tx, human });
    }

    // Legacy tx
    if (base.dataType === 1) {
      const signBytes = Buffer.from(reqObj.getSignData());
      const decoded = rlp.decode(signBytes);
      const [nonceBuf, gasPriceBuf, gasLimitBuf, toBuf, valueBuf, dataBuf] = decoded;

      const chainId = base.chainId || 0;
      const toAddr = ensure0x(readHex(toBuf));
      const valueWei = readBig(valueBuf);
      const dataHex = readHex(dataBuf);
      const selector = dataHex.slice(0, 8);

      let action;
      let tokenMeta = null;

      if (dataHex && selector === 'a9059cbb' && dataHex.length === 8 + 64 + 64) {
        const toArg = dataHex.slice(8, 8 + 64);
        const amtArg = dataHex.slice(8 + 64, 8 + 128);
        const recipient = ensure0x(toArg.slice(24));
        const amountRaw = BigInt('0x' + amtArg);

        const key = `${chainId}:${normalizeAddr(toAddr)}`;
        const meta = TOKEN_DECIMALS[key] || { symbol: 'ERC20', decimals: 18 };
        tokenMeta = meta;

        action = {
          kind: 'erc20.transfer',
          tokenContract: toAddr,
          tokenSymbol: meta.symbol,
          tokenDecimals: meta.decimals,
          recipient,
          amountRaw: amountRaw.toString(),
          humanAmount: formatUnits(amountRaw, meta.decimals),
        };
      } else if (dataHex) {
        action = { kind: 'contract.call', to: toAddr, selector: '0x' + selector, dataLengthBytes: dataHex.length / 2 };
      } else if (valueWei > 0n) {
        action = { kind: 'eth.transfer', recipient: toAddr, valueWei: valueWei.toString(), valueEth: formatUnits(valueWei, 18) };
      } else {
        action = { kind: 'no-value-no-data', to: toAddr };
      }

      const tx = {
        type: 'legacy',
        chainId,
        nonce: readNum(nonceBuf),
        gasPriceWei: readBig(gasPriceBuf).toString(),
        gasPrice: formatGwei(readBig(gasPriceBuf)),
        gasLimit: readNum(gasLimitBuf),
        to: toAddr,
        valueWei: valueWei.toString(),
        valueEth: formatUnits(valueWei, 18),
        action,
      };

      const human = {
        fromAddress,
        toAddress: action.recipient || tx.to,
        token: action.kind === 'erc20.transfer' ? (tokenMeta?.symbol || 'ERC20') : 'ETH',
        amount: action.kind === 'erc20.transfer' ? action.humanAmount : tx.valueEth,
        details: action,
        fees: { gasPrice: tx.gasPrice, gasLimit: tx.gasLimit },
        summary:
          action.kind === 'erc20.transfer'
            ? `Send ${action.humanAmount} ${tokenMeta?.symbol || 'ERC20'} to ${action.recipient}`
            : action.kind === 'eth.transfer'
              ? `Send ${tx.valueEth} ETH to ${action.recipient}`
              : action.kind === 'contract.call'
                ? `Contract call to ${action.to} (selector ${action.selector})`
                : 'No ETH value and no calldata',
      };

      return res.json({ ...base, tx, human });
    }

    // EIP-712 / personal: return raw only (UI is already defensive)
    return res.json({ ...base, note: 'This request type is not decoded by this demo.' });
  } catch (e) {
    return res.status(500).json({ error: e?.message || String(e) });
  }
});

app.post('/api/sign', (req, res) => {
  try {
    const { privateKeyHex } = req.body || {};
    if (!privateKeyHex || !/^[0-9a-fA-F]{64}$/.test(privateKeyHex)) {
      return res.status(400).json({ error: 'Provide { privateKeyHex } as 64 hex chars (no 0x).' });
    }
    if (!lastUR) {
      return res.status(400).json({ error: 'No assembled UR yet. Scan the animated QR first.' });
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
    const msgHash = keccak256('0x' + signBytes.toString('hex'));

    const wallet = new Wallet('0x' + privateKeyHex);
    const sig = wallet.signingKey.sign(msgHash);
    const yParity = (sig.yParity !== undefined) ? sig.yParity : (sig.v % 2);

    const rBuf = Buffer.from(sig.r.slice(2), 'hex');
    const sBuf = Buffer.from(sig.s.slice(2), 'hex');
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
    console.error('SIGN ERROR:', e);
    res.status(500).json({ error: String(e?.message || e) });
  }
});

// ------------------------
// Optional: UI-triggered shutdown (LOCAL ONLY)
// ------------------------
function isLocalRequest(req) {
  const ra = req.socket?.remoteAddress || '';
  return ra === '127.0.0.1' || ra === '::1' || ra === '::ffff:127.0.0.1';
}

const server = app.listen(PORT, () => {
  console.log(`👉 Open http://localhost:${PORT}`);
});

// Local-only shutdown endpoint.
// This lets your "Clear & Close" button tell Node to exit.
app.post('/api/shutdown', (req, res) => {
  if (!isLocalRequest(req)) {
    return res.status(403).json({ error: 'Forbidden' });
  }

  res.json({ ok: true, shuttingDown: true });

  // Allow the response to flush, then stop accepting new connections and exit.
  setTimeout(() => {
    server.close(() => process.exit(0));
  }, 100);
});
