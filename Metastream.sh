#!/usr/bin/env sh

# --- change the port if you like ---
PORT=3000

# go to this script's folder
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR" || exit 1

# start the server in the background
node server.js >/dev/null 2>&1 &

# give the server a moment to boot
sleep 2

# open the default browser
if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "http://localhost:$PORT/"
else
    echo "Server started. Open http://localhost:$PORT/ in your browser."
fi
