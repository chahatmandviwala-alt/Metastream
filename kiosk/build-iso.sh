#!/usr/bin/env bash
# Run ONLY inside a privileged Debian 12 (bookworm) build container.
set -Eeuo pipefail
export DEBIAN_FRONTEND=noninteractive

ROOT="$(pwd)"
if [[ ! -f "$ROOT/package-lock.json" || ! -f "$ROOT/electron/main.js" || ! -f "$ROOT/kiosk/harden-electron.js" ]]; then
  echo 'ERROR: Run this from the Metastream repository root.' >&2
  exit 1
fi

printf '\n== Install ISO build tools ==\n'
apt-get update
apt-get install -y --no-install-recommends \
  live-build debootstrap xorriso squashfs-tools \
  syslinux isolinux syslinux-utils \
  grub-pc-bin grub-efi-amd64-bin grub-common mtools dosfstools \
  ca-certificates file python3 git

printf '\n== Install the app and build its unpacked Linux binary ==\n'
npm ci --no-audit --no-fund
node kiosk/harden-electron.js
npx --no-install electron-builder --linux dir --x64 --publish never

APP_DIR="$ROOT/dist/linux-unpacked"
if [[ ! -d "$APP_DIR" ]]; then
  echo "ERROR: Electron unpacked directory not found: $APP_DIR" >&2
  exit 1
fi
if [[ -x "$APP_DIR/metastream" ]]; then
  APP_EXEC=metastream
elif [[ -x "$APP_DIR/Metastream" ]]; then
  APP_EXEC=Metastream
else
  echo 'ERROR: Could not identify the Electron executable.' >&2
  ls -l "$APP_DIR" >&2
  exit 1
fi

printf '\n== Set up Debian Live ISO ==\n'
WORK="$ROOT/.kiosk-live-build"
rm -rf "$WORK"
mkdir -p "$WORK"
cd "$WORK"
lb config \
  --distribution bookworm \
  --architectures amd64 \
  --binary-images iso-hybrid \
  --bootloaders 'syslinux,grub-efi' \
  --debian-installer none \
  --apt-recommends false \
  --apt-indices false \
  --archive-areas main \
  --bootappend-live 'boot=live nopersistence swap=false ipv6.disable=1 live-config.nottyautologin'

mkdir -p config/package-lists \
  config/includes.chroot/opt/metastream \
  config/includes.chroot/etc/systemd/system/getty@tty1.service.d \
  config/includes.chroot/etc/X11/xorg.conf.d \
  config/includes.chroot/etc/X11 \
  config/includes.chroot/etc/modprobe.d \
  config/includes.chroot/etc/systemd/journald.conf.d \
  config/includes.chroot/etc/systemd/system \
  config/includes.chroot/usr/local/bin \
  config/hooks/live

cat > config/package-lists/metastream-kiosk.list.chroot <<'PACKAGES'
linux-image-amd64
live-boot
live-config
live-config-systemd
systemd-sysv
udev
xserver-xorg-core
xserver-xorg-video-all
xserver-xorg-input-libinput
xserver-xorg-legacy
xinit
x11-xserver-utils
x11-utils
libgl1
libegl1
libgl1-mesa-dri
matchbox-window-manager
dbus-x11
libgtk-3-0
libnss3
libasound2
libgbm1
libxss1
libxtst6
libx11-xcb1
libxkbcommon0
libatk-bridge2.0-0
libcups2
libdrm2
libxcomposite1
libxdamage1
libxrandr2
libxshmfence1
fonts-dejavu-core
ca-certificates
nftables
rfkill
iproute2
user-setup
PACKAGES

cp -a "$APP_DIR/." config/includes.chroot/opt/metastream/
ln -s "/opt/metastream/$APP_EXEC" config/includes.chroot/usr/local/bin/metastream

cat > config/includes.chroot/etc/nftables.conf <<'NFT'
#!/usr/sbin/nft -f
flush ruleset
# The app's HTTP API operates only on 127.0.0.1. No external IP traffic is permitted.
table inet filter {
  chain input {
    type filter hook input priority 0; policy drop;
    iifname "lo" accept
  }
  chain output {
    type filter hook output priority 0; policy drop;
    oifname "lo" accept
  }
  chain forward {
    type filter hook forward priority 0; policy drop;
  }
}
NFT

cat > config/includes.chroot/etc/modprobe.d/metastream-no-network.conf <<'MODPROBE'
# Defense in depth; not an exhaustive list of all possible network hardware.
blacklist iwlwifi
blacklist ath9k
blacklist ath10k_pci
blacklist rtw88_pci
blacklist btusb
blacklist bluetooth
blacklist r8169
blacklist e1000e
blacklist igc
blacklist cdc_ether
blacklist rndis_host
blacklist usbnet
MODPROBE

cat > config/includes.chroot/etc/systemd/system/getty@tty1.service.d/autologin.conf <<'GETTY'
[Service]
ExecStart=
ExecStart=-/sbin/agetty --autologin kiosk --noclear %I $TERM
Type=idle
GETTY

cat > config/includes.chroot/etc/X11/Xwrapper.config <<'XWRAPPER'
allowed_users=console
needs_root_rights=auto
XWRAPPER

cat > config/includes.chroot/etc/X11/xorg.conf.d/50-metastream-kiosk.conf <<'XCONF'
Section "ServerFlags"
  Option "DontVTSwitch" "true"
  Option "DontZap" "true"
  Option "DontZoom" "true"
EndSection
XCONF

cat > config/includes.chroot/etc/systemd/journald.conf.d/kiosk.conf <<'JOURNAL'
[Journal]
Storage=volatile
RuntimeMaxUse=16M
JOURNAL

cat > config/includes.chroot/usr/local/bin/metastream-start-session <<'STARTSESSION'
#!/bin/bash
# Called after tty1 auto-login. Keep failures visible instead of restarting
# agetty and hiding Xorg errors behind a blinking cursor.
set -u
export HOME=/home/kiosk
cd "$HOME" || exit 1
LOG="$HOME/metastream-startx.log"  # Volatile: live system uses RAM overlay.
printf '\n[Metastream] tty1 login worked; starting graphical kiosk...\n'
printf '[Metastream] If the screen remains black, startup errors will appear here.\n'
/usr/bin/startx /usr/local/bin/metastream-kiosk-session -- :0 vt1 -nolisten tcp >"$LOG" 2>&1
RC=$?
printf '\n[Metastream] Graphical session exited (code %s).\n' "$RC"
printf '[Metastream] Last lines of Xorg/startx log:\n'
tail -n 65 "$LOG" 2>/dev/null || true
printf '\n[Metastream] Startup stopped for diagnosis; no login shell is available.\n'
# Avoid rapidly restarting getty and losing the failure message.
while :; do sleep 3600; done
STARTSESSION
chmod 0755 config/includes.chroot/usr/local/bin/metastream-start-session

cat > config/includes.chroot/usr/local/bin/metastream-kiosk-session <<'SESSION'
#!/bin/sh
# Called from a login on tty1; no menu, no conventional desktop.
export METASTREAM_KIOSK=1
export HOST=127.0.0.1
export PORT=43110
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY

/usr/bin/xset s off 2>/dev/null || true
/usr/bin/xset -dpms 2>/dev/null || true
/usr/bin/xset s noblank 2>/dev/null || true
# A dark-blue background indicates Xorg is running even before Electron starts.
/usr/bin/xsetroot -solid '#25364a' 2>/dev/null || true
/usr/bin/matchbox-window-manager -use_titlebar no &

# Electron writes startup diagnostics to RAM, never to disk persistence.
APPLOG="$HOME/metastream-electron.log"
while :; do
  echo '[Metastream] Starting Electron...' >> "$APPLOG"
  START=$(date +%s)
  /usr/local/bin/metastream --ozone-platform=x11 --disable-gpu >> "$APPLOG" 2>&1
  RC=$?
  echo "[Metastream] Electron exited with status $RC" >> "$APPLOG"
  NOW=$(date +%s)
  if [ $((NOW - START)) -lt 12 ]; then
    # Don't silently spin forever when Electron fails to start.
    /usr/bin/xmessage -center -timeout 12 \
      "Metastream could not start (exit code $RC).\nStartup log: /home/kiosk/metastream-electron.log\nThis diagnostic message will retry automatically." \
      >/dev/null 2>&1 || true
  fi
  sleep 2
done
SESSION
chmod 0755 config/includes.chroot/usr/local/bin/metastream-kiosk-session

cat > config/includes.chroot/usr/local/bin/metastream-network-down <<'NETWORK'
#!/bin/sh
# Disable every external interface visible when this service runs.
/usr/sbin/rfkill block all 2>/dev/null || true
for nic in /sys/class/net/*; do
  name="${nic##*/}"
  [ "$name" = lo ] && continue
  /usr/sbin/ip link set dev "$name" down 2>/dev/null || true
done
NETWORK
chmod 0755 config/includes.chroot/usr/local/bin/metastream-network-down

cat > config/includes.chroot/etc/systemd/system/metastream-network-down.service <<'NETUNIT'
[Unit]
Description=Disable hardware network interfaces for Metastream kiosk
After=systemd-udev-settle.service
Before=getty@tty1.service

[Service]
Type=oneshot
ExecStart=/usr/local/bin/metastream-network-down
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
NETUNIT

cat > config/hooks/live/010-metastream-kiosk.hook.chroot <<'HOOK'
#!/bin/sh
set -eu

# Default kiosk user: no password, no sudo privileges.
if ! id kiosk >/dev/null 2>&1; then
  useradd -m -s /bin/bash kiosk
fi
passwd -l kiosk >/dev/null 2>&1 || true
passwd -l root >/dev/null 2>&1 || true
for group in video render audio input; do
  if getent group "$group" >/dev/null; then usermod -aG "$group" kiosk; fi
done

cat > /home/kiosk/.bash_profile <<'PROFILE'
# tty1 is the only permitted visible login; replace shell with X11 kiosk session.
if [ "$(tty)" = /dev/tty1 ] && [ -z "${DISPLAY:-}" ]; then
    exec /usr/local/bin/metastream-start-session
fi
# Any non-kiosk shell returns immediately rather than remaining interactive.
exit
PROFILE
chown kiosk:kiosk /home/kiosk/.bash_profile
chmod 0644 /home/kiosk/.bash_profile

# Keep Chromium's sandbox; this helper is the standard fallback if user namespaces are unavailable.
if [ -f /opt/metastream/chrome-sandbox ]; then
  chown root:root /opt/metastream/chrome-sandbox
  chmod 4755 /opt/metastream/chrome-sandbox
fi

# Fail ISO construction if the packaged Electron binary lacks shared libraries.
# A missing library often causes silent instant exit once the graphical session starts.
if ldd /usr/local/bin/metastream | grep -q 'not found'; then
  echo 'ERROR: Electron has missing shared libraries in live system:' >&2
  ldd /usr/local/bin/metastream | grep 'not found' >&2 || true
  exit 1
fi

systemctl enable getty@tty1.service
systemctl enable nftables.service
systemctl enable metastream-network-down.service
for svc in NetworkManager.service systemd-networkd.service wpa_supplicant.service bluetooth.service; do
  systemctl mask "$svc" 2>/dev/null || true
done
# Only tty1 autologins; other gettys do not offer a shell.
for n in 2 3 4 5 6; do
  systemctl mask "getty@tty${n}.service" 2>/dev/null || true
done
# The running live system regenerates the machine ID in RAM.
: > /etc/machine-id
HOOK
chmod 0755 config/hooks/live/010-metastream-kiosk.hook.chroot

printf '\n== Build image (downloads OS packages only during CI) ==\n'
lb build
ISO="$(find . -maxdepth 1 -type f -name '*.iso' -print -quit)"
if [[ -z "$ISO" ]]; then
  echo 'ERROR: live-build did not output an ISO.' >&2
  exit 1
fi
mkdir -p "$ROOT/out"
cp "$ISO" "$ROOT/out/Metastream-kiosk-amd64.iso"
printf '\nISO created: %s\n' "$ROOT/out/Metastream-kiosk-amd64.iso"
ls -lh "$ROOT/out/Metastream-kiosk-amd64.iso"
