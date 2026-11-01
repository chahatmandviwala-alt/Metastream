/*
 * Build-time-only transformations for the separate kiosk ISO build.
 * The original Electron source in Git remains untouched; CI's checkout is ephemeral.
 * Fail loudly if the repository layout changes instead of silently shipping an unsafe build.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');

function replaceExactly(file, original, replacement) {
  let source = fs.readFileSync(file, 'utf8');
  const first = source.indexOf(original);
  if (first < 0 || source.indexOf(original, first + 1) >= 0) {
    throw new Error(`Expected exactly one occurrence in ${file}: ${original}`);
  }
  source = source.replace(original, replacement);
  fs.writeFileSync(file, source);
}

const server = path.join(root, 'server.js');
replaceExactly(
  server,
  'const PORT = process.env.PORT || 3000;',
  'const PORT = process.env.PORT || 3000;\nconst HOST = "127.0.0.1"; // Kiosk: never listen on an external interface'
);
replaceExactly(
  server,
  'const server = app.listen(PORT, () => {',
  'const server = app.listen(PORT, HOST, () => {'
);

const main = path.join(root, 'electron', 'main.js');
replaceExactly(
  main,
  '    fullscreen: true,',
  '    fullscreen: true,\n    kiosk: true,\n    autoHideMenuBar: true,'
);
replaceExactly(
  main,
  '      contextIsolation: true,',
  '      contextIsolation: true,\n      sandbox: true,\n      devTools: false,'
);
replaceExactly(
  main,
  '  mainWindow.loadURL(URL);',
  `  // Kiosk: deny popups, off-origin navigation, and ordinary developer shortcuts.
  mainWindow.setMenuBarVisibility(false);
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  const kioskOrigin = new URL(URL).origin;
  mainWindow.webContents.on('will-navigate', (event, destination) => {
    try {
      if (new URL(destination).origin !== kioskOrigin) event.preventDefault();
    } catch (_) { event.preventDefault(); }
  });
  mainWindow.webContents.on('before-input-event', (event, input) => {
    const key = String(input.key || '').toLowerCase();
    if (key === 'f12' || key === 'f5' ||
        (input.alt && key === 'f4') ||
        ((input.control || input.meta) &&
          ['r', 'w', 'n', 'o', 'p', 's', 'i', 'j', 'u'].includes(key))) {
      event.preventDefault();
    }
  });
  mainWindow.webContents.session.setPermissionRequestHandler((contents, permission, callback) => {
    // Allow the camera only for Metastream's local page. Deny all other permissions.
    let localPage = false;
    try { localPage = new URL(contents.getURL()).origin === kioskOrigin; } catch (_) {}
    callback(permission === 'media' && localPage && contents.id === mainWindow.webContents.id);
  });

  mainWindow.loadURL(URL);`
);
console.log('Electron entrypoint and Express listener prepared for offline kiosk build.');
