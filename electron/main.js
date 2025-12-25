const { app, BrowserWindow } = require("electron");
const { fork } = require("child_process");
const path = require("path");

let mainWindow = null;
let serverProcess = null;

// Keep this stable across platforms. You can override via env if needed.
const PORT = process.env.PORT || "43110";
const HOST = process.env.HOST || "127.0.0.1";
const URL = `http://${HOST}:${PORT}`;

function startServer() {
  const serverPath = path.join(__dirname, "..", "server.js");

  serverProcess = fork(serverPath, [], {
    env: {
      ...process.env,
      PORT,
      HOST,
    },
    stdio: "inherit",
  });

  serverProcess.on("exit", (code, signal) => {
    // If the server dies, close the app (or you could show an error window).
    if (!app.isQuiting) {
      console.error(`server.js exited (code=${code}, signal=${signal})`);
      app.quit();
    }
  });

  serverProcess.on("error", (err) => {
    console.error("Failed to start server.js:", err);
    if (!app.isQuiting) app.quit();
  });
}

function createWindow() {
  const iconPath = process.platform === "win32"
    ? path.join(__dirname, "..", "build", "icon.ico")
    : path.join(__dirname, "..", "build", "icon.png");

  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    show: true,
    icon: iconPath, // Safe across platforms (Windows prefers .ico, Linux .png)
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
    },
  });

  mainWindow.loadURL(URL);

  // Helpful if the server isn't ready yet or the port is wrong
  mainWindow.webContents.on("did-fail-load", (_event, errorCode, errorDescription, validatedURL) => {
    console.error("did-fail-load:", { errorCode, errorDescription, validatedURL });
    if (!mainWindow) return;

    mainWindow.loadURL(
      "data:text/plain;charset=utf-8," +
        encodeURIComponent(
          `Failed to load:\n${validatedURL}\n\nError ${errorCode}: ${errorDescription}\n\n` +
          `Check that server.js is listening on ${URL}\n`
        )
    );
  });

  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

function stopServer() {
  if (!serverProcess || serverProcess.killed) return;

  try {
    // SIGTERM is the most portable "please exit" signal.
    serverProcess.kill("SIGTERM");
  } catch (e) {
    try {
      serverProcess.kill();
    } catch (_) {}
  }
}

app.whenReady().then(() => {
  startServer();
  createWindow();

  // macOS: re-create window when clicking dock icon and no windows are open
  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on("before-quit", () => {
  app.isQuiting = true;
  stopServer();
});

// Windows/Linux: quit when all windows closed
app.on("window-all-closed", () => {
  app.quit();
});

// Extra safety in case the process exits unexpectedly
process.on("exit", () => stopServer());
process.on("SIGINT", () => {
  stopServer();
  process.exit(0);
});
process.on("SIGTERM", () => {
  stopServer();
  process.exit(0);
});
