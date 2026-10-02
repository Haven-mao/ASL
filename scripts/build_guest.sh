#!/usr/bin/env bash
set -euo pipefail
ASL_SOURCE=$(cd "$(dirname "$0")/.." && pwd)
ASL_GUEST_WORK=${ASL_GUEST_WORK:-"$ASL_SOURCE/build/guest-work"}
ASL_GUEST_OUT=${ASL_GUEST_OUT:-"$ASL_SOURCE/build/guest"}
ASL_CODEX_ARCHIVE=${ASL_CODEX_ARCHIVE:?Set to the verified Codex 0.160.0 ARM64 archive}
python3 "$ASL_SOURCE/scripts/sync_asltree.py"
ASL_MOUNT="$ASL_GUEST_WORK/root"
ASL_RAW="$ASL_GUEST_WORK/root.raw"
ASL_KEYS="$ASL_GUEST_WORK/keys"
[ "$(id -u)" = 0 ] || { printf 'Run as root in Linux/WSL.\n' >&2; exit 1; }
mkdir -p "$ASL_MOUNT" "$ASL_KEYS" "$ASL_GUEST_OUT"
cleanup(){ mountpoint -q "$ASL_MOUNT/proc" && umount "$ASL_MOUNT/proc" || true; mountpoint -q "$ASL_MOUNT" && umount "$ASL_MOUNT" || true; }
trap cleanup EXIT
if [ ! -f "$ASL_RAW" ]; then truncate -s 6G "$ASL_RAW"; mkfs.ext4 -q -L asl-root -m 0 "$ASL_RAW"; fi
mountpoint -q "$ASL_MOUNT" || mount -o loop "$ASL_RAW" "$ASL_MOUNT"
for key in archive-key-12 archive-key-12-security release-12; do
  [ -f "$ASL_KEYS/$key.asc" ] || curl -fsSL --retry 3 "https://ftp-master.debian.org/keys/$key.asc" -o "$ASL_KEYS/$key.asc"
done
cat "$ASL_KEYS"/*.asc | gpg --batch --yes --dearmor -o "$ASL_KEYS/archive.gpg"
if [ ! -f "$ASL_MOUNT/etc/debian_version" ]; then
  debootstrap --arch=arm64 --foreign --variant=minbase --include=ca-certificates --keyring="$ASL_KEYS/archive.gpg" bookworm "$ASL_MOUNT" https://deb.debian.org/debian
fi
if [ ! -f "$ASL_MOUNT/.asl-base-ready" ]; then chroot "$ASL_MOUNT" /debootstrap/debootstrap --second-stage; touch "$ASL_MOUNT/.asl-base-ready"; fi
cat > "$ASL_MOUNT/etc/apt/sources.list" <<'EOF'
deb https://deb.debian.org/debian bookworm main
deb https://deb.debian.org/debian bookworm-updates main
deb https://security.debian.org/debian-security bookworm-security main
EOF
printf '#!/bin/sh\nexit 101\n' > "$ASL_MOUNT/usr/sbin/policy-rc.d"
chmod 755 "$ASL_MOUNT/usr/sbin/policy-rc.d"
printf 'nameserver 223.5.5.5\nnameserver 1.1.1.1\n' > "$ASL_MOUNT/etc/resolv.conf"
mountpoint -q "$ASL_MOUNT/proc" || mount -t proc proc "$ASL_MOUNT/proc"
chroot "$ASL_MOUNT" /bin/sh -c 'export DEBIAN_FRONTEND=noninteractive; apt-get update -qq; apt-get install -y --no-install-recommends systemd-sysv systemd-timesyncd dbus openssh-server sudo python3 curl git ca-certificates iproute2 iputils-ping procps less vim-tiny socat tmux apparmor docker.io linux-image-cloud-arm64 initramfs-tools'
touch "$ASL_MOUNT/.asl-packages-ready"
python3 "$ASL_SOURCE/scripts/configure_guest.py" --root "$ASL_MOUNT" --codex-archive "$ASL_CODEX_ARCHIVE" --output "$ASL_GUEST_OUT"
sync
cleanup
bash "$ASL_SOURCE/scripts/seal_guest.sh" "$ASL_RAW" "$ASL_GUEST_OUT" "${ASL_SYSTEM_BUNDLE:-$ASL_SOURCE/../ASL-system.asl}"
