#!/usr/bin/env bash
# Interactive local USB writer for the Metastream kiosk ISO with a LUKS2-encrypted
# persistence partition that persists ONLY a single directory (default: /home/kiosk/wallet).
#
# Run this locally on your Linux host (e.g., MX Live) with sudo:
#   sudo bash kiosk/flash-luks-usb.sh /path/to/Metastream-kiosk-amd64.iso
#
# Optional flags:
#   --persist-dir  PATH   Directory inside kiosk OS to persist (default: /home/kiosk/wallet)
#   --persist-size SIZE   LUKS2 partition size in M or G       (default: 256M)
#   --kiosk-uid    UID    UID of kiosk user inside live OS     (default: 1000)
#   --kiosk-gid    GID    GID of kiosk user inside live OS     (default: 1000)
set -Eeuo pipefail

PERSIST_DIR="/home/kiosk/wallet"
PERSIST_SIZE="256M"
KIOSK_UID="1000"
KIOSK_GID="1000"
ISO_PATH=""

usage() {
  cat <<'USAGE'
Usage:
  sudo bash kiosk/flash-luks-usb.sh [options] <path-to-Metastream-kiosk-amd64.iso>

Options:
  --persist-dir  PATH   Absolute path inside kiosk OS to persist (default: /home/kiosk/wallet)
  --persist-size SIZE   LUKS2 partition size, e.g. 256M or 1G   (default: 256M)
  --kiosk-uid    UID    kiosk user UID inside live OS            (default: 1000)
  --kiosk-gid    GID    kiosk user GID inside live OS            (default: 1000)
  -h, --help            Show this help message
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --persist-dir)
      PERSIST_DIR="${2:-}"
      shift 2
      ;;
    --persist-size)
      PERSIST_SIZE="${2:-}"
      shift 2
      ;;
    --kiosk-uid)
      KIOSK_UID="${2:-}"
      shift 2
      ;;
    --kiosk-gid)
      KIOSK_GID="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "ERROR: Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -n "$ISO_PATH" ]]; then
        echo "ERROR: Unexpected extra argument: $1" >&2
        usage >&2
        exit 1
      fi
      ISO_PATH="$1"
      shift
      ;;
  esac
done

if [[ -z "$ISO_PATH" ]]; then
  echo "ERROR: Please provide the path to Metastream-kiosk-amd64.iso." >&2
  usage >&2
  exit 1
fi

if [[ $EUID -ne 0 ]]; then
  echo "ERROR: This script must be run with sudo/root privileges." >&2
  exit 1
fi

if [[ ! -s "$ISO_PATH" ]]; then
  echo "ERROR: ISO file not found or empty: $ISO_PATH" >&2
  exit 1
fi

# Validate persistence directory (must be an absolute path without .. segments).
if [[ "$PERSIST_DIR" != /* || "$PERSIST_DIR" == "/" || "$PERSIST_DIR" == *"/.."* || "$PERSIST_DIR" == *"../"* ]]; then
  echo "ERROR: --persist-dir must be a safe non-root absolute path (got: $PERSIST_DIR)" >&2
  exit 1
fi
PERSIST_DIR="${PERSIST_DIR%/}"

# Parse persistence size into MiB.
if [[ "$PERSIST_SIZE" =~ ^([0-9]+)[Mm]$ ]]; then
  PERSIST_MIB="${BASH_REMATCH[1]}"
elif [[ "$PERSIST_SIZE" =~ ^([0-9]+)[Gg]$ ]]; then
  PERSIST_MIB=$(( BASH_REMATCH[1] * 1024 ))
else
  echo "ERROR: --persist-size must end with M or G (e.g. 256M, 1G); got: $PERSIST_SIZE" >&2
  exit 1
fi
if (( PERSIST_MIB < 32 )); then
  echo "ERROR: --persist-size must be at least 32M (LUKS2 header is 16M + ext4 metadata)." >&2
  exit 1
fi

# Check required host utilities upfront before modifying anything.
MISSING_CMDS=()
for cmd in lsblk parted wipefs mkfs.vfat mkfs.ext4 cryptsetup mount umount findmnt udevadm sed awk grep; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    MISSING_CMDS+=("$cmd")
  fi
done
if (( ${#MISSING_CMDS[@]} > 0 )); then
  echo "ERROR: Missing required tools: ${MISSING_CMDS[*]}" >&2
  echo "Install them first (e.g., sudo apt-get update && sudo apt-get install -y parted dosfstools e2fsprogs cryptsetup grub-efi-amd64-bin grub-pc-bin)." >&2
  exit 1
fi

# Detect whether lsblk supports plural MOUNTPOINTS (util-linux >= 2.37).
MOUNT_COL="MOUNTPOINT"
if lsblk -dn -o MOUNTPOINTS >/dev/null 2>&1; then
  MOUNT_COL="MOUNTPOINTS"
fi

# Temporary mountpoints & LUKS mapper name, always cleaned up on exit.
WORK_DIR="$(mktemp -d /tmp/kiosk-usb-XXXXXX)"
ISO_MNT="$WORK_DIR/iso"
BOOT_MNT="$WORK_DIR/boot"
PERSIST_MNT="$WORK_DIR/persist"
LUKS_NAME="kiosk_persist"
mkdir -p "$ISO_MNT" "$BOOT_MNT" "$PERSIST_MNT"

cleanup() {
  local rc=$?
  set +e
  if mountpoint -q "$PERSIST_MNT" 2>/dev/null; then
    umount "$PERSIST_MNT"
  fi
  if [[ -e "/dev/mapper/$LUKS_NAME" ]]; then
    cryptsetup close "$LUKS_NAME"
  fi
  if mountpoint -q "$BOOT_MNT" 2>/dev/null; then
    umount "$BOOT_MNT"
  fi
  if mountpoint -q "$ISO_MNT" 2>/dev/null; then
    umount "$ISO_MNT"
  fi
  sync
  rmdir "$PERSIST_MNT" "$BOOT_MNT" "$ISO_MNT" "$WORK_DIR" 2>/dev/null || true
  exit "$rc"
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# Pre-flight check inside the ISO (before touching any USB drive)
# ---------------------------------------------------------------------------
printf '\n== Step 2: Pre-flight LUKS2 check inside ISO ==\n'
mount -o loop,ro "$ISO_PATH" "$ISO_MNT"

INITRD_FILE=""
for candidate in "$ISO_MNT"/live/initrd.img* "$ISO_MNT"/live/initrd*; do
  if [[ -f "$candidate" ]]; then
    INITRD_FILE="$candidate"
    break
  fi
done
if [[ -z "$INITRD_FILE" ]]; then
  echo "ERROR: No initrd found under /live/ inside $ISO_PATH." >&2
  exit 1
fi
if [[ ! -f "$ISO_MNT/live/filesystem.squashfs" ]]; then
  echo "ERROR: live/filesystem.squashfs not found inside $ISO_PATH." >&2
  exit 1
fi

if command -v lsinitramfs >/dev/null 2>&1; then
  INITRD_LIST="$(lsinitramfs "$INITRD_FILE")"
else
  INITRD_LIST="$(gzip -dc "$INITRD_FILE" 2>/dev/null | cpio -t 2>/dev/null || true)"
fi

for pattern in '(^|/)sbin/cryptsetup$' '(^|/)lib/cryptsetup/askpass$' 'dm[-_]crypt\.ko' 'xts\.ko' 'algif_skcipher\.ko'; do
  if ! printf '%s\n' "$INITRD_LIST" | grep -Eq "$pattern"; then
    echo "ERROR: Pre-flight FAILED: $INITRD_FILE is missing required LUKS2 component matching: $pattern" >&2
    echo "STOPPING before touching any disk. Rebuild the ISO with cryptsetup-initramfs." >&2
    exit 1
  fi
done
echo "Pre-flight PASSED: cryptsetup, askpass, and LUKS2 kernel modules are present in $(basename "$INITRD_FILE")."

# ---------------------------------------------------------------------------
# Step 3: Safely identify and confirm the target USB drive
# ---------------------------------------------------------------------------
printf '\n== Step 3: Identify target USB drive ==\n'
lsblk -o "NAME,SIZE,MODEL,TRAN,$MOUNT_COL"

echo ""
echo "Currently mounted/active disks (AUTOMATICALLY EXCLUDED):"
EXCLUDED_DISKS=()
while IFS= read -r dev; do
  [[ -n "$dev" ]] || continue
  # Check if disk or any child partition has a mountpoint or active swap/dm holder.
  if lsblk -nr -o "$MOUNT_COL" "$dev" | grep -q '[^[:space:]]'; then
    EXCLUDED_DISKS+=("$dev")
    MODEL="$(lsblk -dn -o MODEL "$dev" | xargs || true)"
    SIZE="$(lsblk -dn -o SIZE "$dev" | xargs || true)"
    TRAN="$(lsblk -dn -o TRAN "$dev" | xargs || true)"
    printf '  - %s (%s, %s, %s) [HAS MOUNTED PARTITIONS]\n' "$dev" "$SIZE" "${MODEL:-unknown}" "${TRAN:-unknown}"
  fi
done < <(lsblk -dn -o PATH,TYPE | awk '$2 == "disk" {print $1}')

echo ""
read -r -p "Enter the EXACT target USB whole-disk path (e.g., /dev/sdb): " TARGET_DISK

if [[ ! -b "$TARGET_DISK" ]]; then
  echo "ERROR: Not a valid block device: $TARGET_DISK" >&2
  exit 1
fi

DISK_TYPE="$(lsblk -dn -o TYPE "$TARGET_DISK" | xargs)"
if [[ "$DISK_TYPE" != "disk" ]]; then
  echo "ERROR: $TARGET_DISK is type '$DISK_TYPE', not a whole 'disk' device (do not pass a partition like /dev/sdb1)." >&2
  exit 1
fi

case "$TARGET_DISK" in
  /dev/loop*|/dev/ram*|/dev/zram*|/dev/sr*)
    echo "ERROR: Refusing to target virtual/optical device: $TARGET_DISK" >&2
    exit 1
    ;;
esac

if lsblk -nr -o "$MOUNT_COL" "$TARGET_DISK" | grep -q '[^[:space:]]'; then
  echo "ERROR: Refusing to wipe $TARGET_DISK because one or more of its partitions are currently mounted!" >&2
  lsblk -o "NAME,SIZE,MODEL,TRAN,$MOUNT_COL" "$TARGET_DISK" >&2
  exit 1
fi

DISK_TRAN="$(lsblk -dn -o TRAN "$TARGET_DISK" | xargs || true)"
if [[ "$DISK_TRAN" != "usb" ]]; then
  echo "WARNING: $TARGET_DISK transport is '${DISK_TRAN:-unknown}' (not 'usb')!" >&2
  read -r -p "Are you 100% sure this is your target USB drive and NOT an internal drive? (yes/no): " NON_USB_CONFIRM
  if [[ "$NON_USB_CONFIRM" != "yes" ]]; then
    echo "Aborted by user. No changes were made."
    exit 1
  fi
fi

# Verify disk capacity fits Partition 1 (ISO + 128 MiB headroom) + Partition 2 (PERSIST_MIB).
ISO_BYTES="$(stat -c '%s' "$ISO_PATH")"
ISO_MIB=$(( (ISO_BYTES + 1048575) / 1048576 ))
BOOT_MIB=$(( ISO_MIB + 128 ))
P1_START_MIB=4
P1_END_MIB=$(( P1_START_MIB + BOOT_MIB ))
P2_END_MIB=$(( P1_END_MIB + PERSIST_MIB ))
DISK_BYTES="$(lsblk -bdn -o SIZE "$TARGET_DISK")"
DISK_MIB=$(( DISK_BYTES / 1048576 ))

if (( DISK_MIB < P2_END_MIB + 8 )); then
  echo "ERROR: $TARGET_DISK is only ${DISK_MIB} MiB, but ${P2_END_MIB} MiB is needed (${BOOT_MIB} MiB boot + ${PERSIST_MIB} MiB LUKS2)." >&2
  exit 1
fi

printf '\nSelected target drive details:\n'
lsblk -o "NAME,SIZE,MODEL,TRAN,FSTYPE,LABEL,$MOUNT_COL" "$TARGET_DISK"
printf '\nPlanned layout on %s:\n' "$TARGET_DISK"
printf '  - Partition 1: %d MiB FAT32 (label: KIOSK_BOOT, bootable/ESP) -> writable ISO + GRUB\n' "$BOOT_MIB"
printf '  - Partition 2: %d MiB LUKS2 -> ext4 (label: persistence) -> ONLY persists %s\n' "$PERSIST_MIB" "$PERSIST_DIR"
printf '\nWARNING: ALL DATA ON %s WILL BE PERMANENTLY ERASED!\n' "$TARGET_DISK"
read -r -p "Type 'WIPE ${TARGET_DISK}' to proceed: " CONFIRM
if [[ "$CONFIRM" != "WIPE ${TARGET_DISK}" ]]; then
  echo "Aborted by user. No changes were made."
  exit 1
fi

# Determine partition device names (/dev/sdX1 vs /dev/nvme0n1p1 / /dev/mmcblk0p1).
if [[ "$TARGET_DISK" =~ [0-9]$ ]]; then
  PART1="${TARGET_DISK}p1"
  PART2="${TARGET_DISK}p2"
else
  PART1="${TARGET_DISK}1"
  PART2="${TARGET_DISK}2"
fi

# ---------------------------------------------------------------------------
# Step 4: Partition USB drive, extract ISO, and install GRUB (UEFI + BIOS)
# ---------------------------------------------------------------------------
printf '\n== Step 4: Partitioning %s and extracting ISO ==\n' "$TARGET_DISK"
wipefs -a "$TARGET_DISK"
parted -s "$TARGET_DISK" mklabel msdos
parted -s -a optimal "$TARGET_DISK" mkpart primary fat32 "${P1_START_MIB}MiB" "${P1_END_MIB}MiB"
parted -s "$TARGET_DISK" set 1 esp on 2>/dev/null || true
parted -s "$TARGET_DISK" set 1 boot on
parted -s -a optimal "$TARGET_DISK" mkpart primary ext4 "${P1_END_MIB}MiB" "${P2_END_MIB}MiB"

partprobe "$TARGET_DISK" 2>/dev/null || true
udevadm settle
sleep 1

if [[ ! -b "$PART1" || ! -b "$PART2" ]]; then
  echo "ERROR: Partition nodes $PART1 / $PART2 did not appear after partitioning." >&2
  exit 1
fi

wipefs -a "$PART1"
mkfs.vfat -F 32 -n KIOSK_BOOT "$PART1"

mount "$PART1" "$BOOT_MNT"
echo "Copying ISO contents onto $PART1 (KIOSK_BOOT)..."
cp -rL "$ISO_MNT/." "$BOOT_MNT/"

# Guard EFI-only video modules in config.cfg so Legacy BIOS GRUB prints no errors.
if [[ -f "$BOOT_MNT/boot/grub/config.cfg" ]]; then
  sed -i \
    -e 's/^[[:space:]]*insmod efi_gop$/    if [ "${grub_platform}" = "efi" ]; then insmod efi_gop; fi/' \
    -e 's/^[[:space:]]*insmod efi_uga$/    if [ "${grub_platform}" = "efi" ]; then insmod efi_uga; fi/' \
    "$BOOT_MNT/boot/grub/config.cfg"
fi

echo "Installing GRUB bootloader..."
UEFI_INSTALLED=0
BIOS_INSTALLED=0

if command -v grub-install >/dev/null 2>&1 && [[ -d /usr/lib/grub/x86_64-efi ]]; then
  grub-install \
    --target=x86_64-efi \
    --efi-directory="$BOOT_MNT" \
    --boot-directory="$BOOT_MNT/boot" \
    --removable \
    --no-nvram \
    --force
  UEFI_INSTALLED=1
elif [[ -f "$BOOT_MNT/EFI/boot/bootx64.efi" || -f "$BOOT_MNT/EFI/BOOT/BOOTX64.EFI" ]]; then
  echo "Note: Host /usr/lib/grub/x86_64-efi not found; retaining UEFI boot files extracted from the ISO."
  UEFI_INSTALLED=1
else
  echo "WARNING: Could not install UEFI GRUB (install grub-efi-amd64-bin if needed)." >&2
fi

if [[ -d "$BOOT_MNT/isolinux" || -d "$ISO_MNT/isolinux" ]]; then
  if command -v grub-install >/dev/null 2>&1 && [[ -d /usr/lib/grub/i386-pc ]]; then
    grub-install \
      --target=i386-pc \
      --boot-directory="$BOOT_MNT/boot" \
      --force \
      "$TARGET_DISK"
    BIOS_INSTALLED=1
  else
    echo "Note: Host /usr/lib/grub/i386-pc not found; skipped BIOS GRUB (install grub-pc-bin if Legacy BIOS boot is needed)."
  fi
fi
echo "Bootloader status: UEFI=${UEFI_INSTALLED}, BIOS=${BIOS_INSTALLED}"

# ---------------------------------------------------------------------------
# Step 5: Enable LUKS persistence in the bootloader
# ---------------------------------------------------------------------------
printf '\n== Step 5: Enabling LUKS2 persistence in bootloader config ==\n'

patch_boot_file() {
  local cfg="$1"
  [[ -f "$cfg" ]] || return 0
  # Remove any leftover nopersistence token and append persistence + persistence-encryption=luks
  # on active boot=live lines (ignoring commented lines).
  awk '
    /^[[:space:]]*(linux|append)[[:space:]].*boot=live/ {
      while (match($0, /[[:space:]]+nopersistence([[:space:]]|$)/)) {
        $0 = substr($0, 1, RSTART - 1) substr($0, RSTART + RLENGTH - (substr($0, RSTART + RLENGTH - 1, 1) ~ /[[:space:]]/ ? 1 : 0))
      }
      sub(/[[:space:]]+$/, "")
      if ($0 !~ /(^|[[:space:]])persistence([[:space:]]|$)/) {
        $0 = $0 " persistence"
      }
      if ($0 !~ /(^|[[:space:]])persistence-encryption=luks([[:space:]]|$)/) {
        $0 = $0 " persistence-encryption=luks"
      }
    }
    { print }
  ' "$cfg" > "${cfg}.tmp"
  mv "${cfg}.tmp" "$cfg"
}

GRUB_CFG="$BOOT_MNT/boot/grub/grub.cfg"
if [[ ! -f "$GRUB_CFG" ]]; then
  echo "ERROR: Expected GRUB config not found at $GRUB_CFG" >&2
  exit 1
fi
patch_boot_file "$GRUB_CFG"

for extra_cfg in \
  "$BOOT_MNT/isolinux/live.cfg" \
  "$BOOT_MNT/isolinux/isolinux.cfg" \
  "$BOOT_MNT/syslinux/live.cfg" \
  "$BOOT_MNT/syslinux/syslinux.cfg" \
  "$BOOT_MNT/syslinux.cfg"; do
  patch_boot_file "$extra_cfg"
done

echo "--- Modified GRUB entries ($GRUB_CFG) ---"
grep -n -C 2 -E '^[[:space:]]*linux[[:space:]]+/live/' "$GRUB_CFG"
if [[ -f "$BOOT_MNT/isolinux/live.cfg" ]]; then
  echo "--- Modified isolinux/live.cfg ---"
  cat "$BOOT_MNT/isolinux/live.cfg"
fi

# ---------------------------------------------------------------------------
# Step 6: Create the LUKS2 persistence partition
# ---------------------------------------------------------------------------
printf '\n== Step 6: Creating LUKS2 persistence partition on %s ==\n' "$PART2"
echo "You will now be prompted directly by cryptsetup to set your LUKS2 passphrase."
echo "(Passphrases are NEVER logged, echoed, or stored by this script.)"
echo ""

wipefs -a "$PART2"
cryptsetup luksFormat --type luks2 "$PART2"

echo ""
echo "Enter your new LUKS2 passphrase once to unlock $PART2 as /dev/mapper/$LUKS_NAME:"
cryptsetup open "$PART2" "$LUKS_NAME"

mkfs.ext4 -F -L persistence "/dev/mapper/$LUKS_NAME"
mount "/dev/mapper/$LUKS_NAME" "$PERSIST_MNT"

# Write persistence.conf with ONLY the single target directory.
printf '%s\n' "$PERSIST_DIR" > "$PERSIST_MNT/persistence.conf"
chmod 0644 "$PERSIST_MNT/persistence.conf"

# Pre-create the persisted directory tree with appropriate ownership/permissions
# so the kiosk user (UID:GID 1000:1000) can read/write .json files inside it.
TARGET_IN_PERSIST="${PERSIST_MNT}${PERSIST_DIR}"
mkdir -p "$TARGET_IN_PERSIST"
if [[ "$PERSIST_DIR" == /home/kiosk/* && -d "$PERSIST_MNT/home/kiosk" ]]; then
  chmod 0755 "$PERSIST_MNT/home"
  chown "${KIOSK_UID}:${KIOSK_GID}" "$PERSIST_MNT/home/kiosk"
  chmod 0755 "$PERSIST_MNT/home/kiosk"
fi
chown "${KIOSK_UID}:${KIOSK_GID}" "$TARGET_IN_PERSIST"
chmod 0700 "$TARGET_IN_PERSIST"

echo ""
echo "--- persistence.conf ---"
cat "$PERSIST_MNT/persistence.conf"
echo "--- Persisted directory permissions ---"
ls -ld "$TARGET_IN_PERSIST"

# Cleanly unmount everything and close the LUKS mapping before final verification.
printf '\n== Closing mounts and LUKS volume ==\n'
umount "$PERSIST_MNT"
cryptsetup close "$LUKS_NAME"
umount "$BOOT_MNT"
umount "$ISO_MNT"
sync

# ---------------------------------------------------------------------------
# Step 7: Final verification
# ---------------------------------------------------------------------------
printf '\n== Step 7: Final Verification ==\n'
lsblk -f "$TARGET_DISK"

echo ""
if lsblk -nr -o "$MOUNT_COL" "$TARGET_DISK" | grep -q '[^[:space:]]'; then
  echo "WARNING: Some partitions on $TARGET_DISK are still mounted!" >&2
  exit 1
fi
if [[ -e "/dev/mapper/$LUKS_NAME" ]]; then
  echo "WARNING: /dev/mapper/$LUKS_NAME is still open!" >&2
  exit 1
fi

echo "Verified: all temporary mounts and /dev/mapper/$LUKS_NAME are closed and synced."
echo "It is now safe to unplug $TARGET_DISK."
