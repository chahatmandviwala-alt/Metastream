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
  // =========================
  // Ethereum Mainnet (1)
  // =========================

  // Stablecoins
  '1:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48': { symbol: 'USDC', decimals: 6 },
  '1:0xdac17f958d2ee523a2206206994597c13d831ec7': { symbol: 'USDT', decimals: 6 },
  '1:0x6b175474e89094c44da98b954eedeac495271d0f': { symbol: 'DAI', decimals: 18 },
  '1:0xdc035d45d973e3ec169d2276ddab16f1e407384f': { symbol: 'USDS', decimals: 18 },

  // ETH derivatives
  '1:0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2': { symbol: 'WETH', decimals: 18 },
  '1:0xae7ab96520de3a18e5e111b5eaab095312d7fe84': { symbol: 'stETH', decimals: 18 },
  '1:0x7f39c581f595b53c5cb19bd0b3f8da6c935e2ca0': { symbol: 'wstETH', decimals: 18 },
  '1:0xcd5fe23c85820f7b72d0926fc9b05b43e359b7ee': { symbol: 'weETH', decimals: 18 },

  // BTC derivatives
  '1:0x2260fac5e5542a773aa44fbcfedf7c193bc2c599': { symbol: 'WBTC', decimals: 8 },
  '1:0x66eff5221ca926636224650fd3b9c497ff828f7d': { symbol: 'multiBTC', decimals: 8 },
  '1:0x9be89d2a4cd102d8fecc6bf9da793be995c22541': { symbol: 'cbBTC', decimals: 8 },

  // Other wrapped assets
  '1:0xb8c77482e45f1f44de1745f52c74426c631bdd52': { symbol: 'BNB', decimals: 18 },
  '1:0xa2e3356610840701bdf5611a53974510ae27e2e1': { symbol: 'WBETH', decimals: 18 },

  // DeFi
  '1:0x514910771af9ca656af840dff83e8264ecf986ca': { symbol: 'LINK', decimals: 18 },
  '1:0x1f9840a85d5af5bf1d1762f925bdaddc4201f984': { symbol: 'UNI', decimals: 18 },
  '1:0x7fc66500c84a76ad7e9c93437bfc5ac33e2ddae9': { symbol: 'AAVE', decimals: 18 },

  // Memes
  '1:0x95ad61b0a150d79219dcf64e1e6cc01f0b64c4ce': { symbol: 'SHIB', decimals: 18 },
  
  // Commodities
  '1:0x68749665ff8d2d112fa859aa293f07a622782f38': { symbol: 'XAUT', decimals: 6 },
  '1:0x45804880de22913dafe09f4980848ece6ecbaf78': { symbol: 'PAXG', decimals: 18 },

  // =========================
  // Base (8453)
  // =========================

  '8453:0x833589fcd6edb6e08f4c7c32d4f71b54bda02913': { symbol: 'USDC', decimals: 6 },
  '8453:0x4200000000000000000000000000000000000006': { symbol: 'WETH', decimals: 18 },
  '8453:0xc1cba3fcea344f92d9239c08c0568f6f2f0ee452': { symbol: 'wstETH', decimals: 18 },
  '8453:0x820c137fa70c8691f0e44dc420a5e53c168921dc': { symbol: 'USDS', decimals: 18 },
  '8453:0xcbb7c0000ab88b473b1f5afd9ef808440eed33bf': { symbol: 'cbBTC', decimals: 8 },
  '8453:0x50c5725949a6f0c72e6c4a641f24049a917db0cb': { symbol: 'DAI', decimals: 18 },

  // =========================
  // Arbitrum One (42161)
  // =========================

  '42161:0xaf88d065e77c8cc2239327c5edb3a432268e5831': { symbol: 'USDC', decimals: 6 },
  '42161:0x82af49447d8a07e3bd95bd0d56f35241523fbab1': { symbol: 'WETH', decimals: 18 },
  '42161:0x2f2a2543b76a4166549f7aab2e75bef0aefc5b0f': { symbol: 'WBTC', decimals: 8 },
  '42161:0x5979d7b546e38e414f7e9822514be443a4800529': { symbol: 'wstETH', decimals: 18 },
  '42161:0x6491c05a82219b8d1479057361ff1654749b876b': { symbol: 'USDS', decimals: 18 },
  '42161:0xcbb7c0000ab88b473b1f5afd9ef808440eed33bf': { symbol: 'cbBTC', decimals: 8 },
  '42161:0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9': { symbol: 'USDT0', decimals: 6 },
  '42161:0xfa7f8980b0f1e64a2062791cc3b0871572f1f7f0': { symbol: 'UNI', decimals: 18 },
  '42161:0x912ce59144191c1204e64559fe8253a0e49e6548': { symbol: 'ARB', decimals: 18 },
  '42161:0xf97f4df75117a78c1a5a0dbb814af92458539fb4': { symbol: 'LINK', decimals: 18 },
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
