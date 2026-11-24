#!/usr/bin/env bash
# Lumen in a new Proxmox LXC. Run on the Proxmox host as root:
#   bash -c "$(curl -fsSL https://raw.githubusercontent.com/Neoo-Blue/lumen/main/deploy/install-lxc.sh)"
set -euo pipefail
say() { printf '\033[1m%s\033[0m\n' "$*"; }
command -v pct >/dev/null || { echo "Run this on a Proxmox host."; exit 1; }

CTID="${CTID:-$(pvesh get /cluster/nextid)}"
STORAGE="${STORAGE:-local-lvm}"
BRIDGE="${BRIDGE:-vmbr0}"
TEMPLATE_STORE="${TEMPLATE_STORE:-local}"

say "Getting the Debian template"
pveam update >/dev/null
TEMPLATE=$(pveam available --section system | awk '/debian-13-standard/ {print $2}' | sort | tail -1)
[ -n "$TEMPLATE" ] || TEMPLATE=$(pveam available --section system | awk '/debian-12-standard/ {print $2}' | sort | tail -1)
pveam list "$TEMPLATE_STORE" | grep -q "$TEMPLATE" || pveam download "$TEMPLATE_STORE" "$TEMPLATE"

say "Creating container $CTID"
pct create "$CTID" "$TEMPLATE_STORE:vztmpl/$TEMPLATE" --hostname lumen --cores 2 --memory 2048 --swap 512 \
  --rootfs "$STORAGE:8" --net0 "name=eth0,bridge=$BRIDGE,ip=dhcp" --unprivileged 1 --features nesting=1 --onboot 1
# give the container a tunnel device so Tailscale can run in normal mode (it falls back to userspace without it)
pct set "$CTID" --dev0 /dev/net/tun 2>/dev/null || {
  printf 'lxc.cgroup2.devices.allow: c 10:200 rwm\nlxc.mount.entry: /dev/net/tun dev/net/tun none bind,create=file\n' >> "/etc/pve/lxc/$CTID.conf"; }
pct start "$CTID"
sleep 8

say "Installing Lumen inside the container"
pct exec "$CTID" -- bash -c "apt-get update -qq && apt-get install -y -qq curl >/dev/null && curl -fsSL ${LUMEN_RAW:-https://raw.githubusercontent.com/Neoo-Blue/lumen/main}/deploy/install-debian.sh | LUMEN_HOSTNAME=${LUMEN_HOSTNAME:-lumen} TS_AUTHKEY=${TS_AUTHKEY:-} bash"
