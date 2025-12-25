#!/usr/bin/env sh

set -e

OUTPUT_FILE="Metastream.sh"

# ensure npm exists
if ! command -v npm >/dev/null 2>&1; then
    echo "Error: npm is not installed or not in PATH."
    echo "Install Node.js from https://nodejs.org and re-run this script."
    exit 1
fi

# go to this script's folder
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "Running npm install..."
npm install

cat > "$OUTPUT_FILE" <<'EOF'
#!/usr/bin/env sh

# --- change the port if you like ---
PORT=3000

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR" || exit 1

node server.js >/dev/null 2>&1 &
sleep 2

if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "http://localhost:$PORT/"
else
    echo "Server started. Open http://localhost:$PORT/ in your browser."
fi
EOF

chmod +x "$OUTPUT_FILE"

echo "Installation complete."
echo "Run the app by double-clicking %OUTPUT_FILE% or with terminal: ./$OUTPUT_FILE"
