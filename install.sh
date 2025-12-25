#!/usr/bin/env sh

set -e

echo
echo "============================"
echo "  Metastream Installation"
echo "============================"
echo

# go to this script's folder
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Check for npm
if ! command -v npm >/dev/null 2>&1; then
  echo "ERROR: Node.js / npm is not installed."
  echo
  echo "Please install Node.js (it includes npm), then run this installer again."
  echo "Download: https://nodejs.org"
  echo
  exit 1
fi

echo "Installing dependencies (this may take a minute)..."
if [ -f package-lock.json ]; then
  npm ci
else
  npm install
fi

OUTPUT_FILE="Metastream.sh"

cat > "$OUTPUT_FILE" <<'EOF'
#!/usr/bin/env sh

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

echo
echo "============================"
echo "  Installation complete."
echo "============================"
echo
echo "Start Metastream by double-clicking Metastream.sh or run in terminal:"
echo "  ./$OUTPUT_FILE"
echo
