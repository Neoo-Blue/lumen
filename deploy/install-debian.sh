#!/usr/bin/env bash
# Lumen on Debian or Ubuntu (a VM, a Proxmox LXC, a small home server). Run as root:
#   curl -fsSL https://raw.githubusercontent.com/Neoo-Blue/lumen/main/deploy/install-debian.sh | bash
set -euo pipefail
REPO="${LUMEN_REPO:-https://github.com/Neoo-Blue/lumen.git}"
HOSTNAME_TS="${LUMEN_HOSTNAME:-lumen}"
say() { printf '\033[1m%s\033[0m\n' "$*"; }
[ "$(id -u)" = 0 ] || { echo "Please run as root (sudo)."; exit 1; }
export DEBIAN_FRONTEND=noninteractive

say "Installing what Lumen needs"
apt-get update -qq
apt-get install -y -qq curl ca-certificates git python3 >/dev/null
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 22 ]; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null
  apt-get install -y -qq nodejs >/dev/null
fi
command -v tailscale >/dev/null || curl -fsSL https://tailscale.com/install.sh | sh

say "Setting up Tailscale (private, no logs sent to Tailscale)"
mkdir -p /etc/default
touch /etc/default/tailscaled
grep -q TS_NO_LOGS_NO_SUPPORT /etc/default/tailscaled || echo 'TS_NO_LOGS_NO_SUPPORT=true' >> /etc/default/tailscaled
# containers without /dev/net/tun use userspace networking; Lumen only needs `tailscale serve`
if [ ! -c /dev/net/tun ]; then sed -i 's/^FLAGS=.*//' /etc/default/tailscaled; echo 'FLAGS="--tun=userspace-networking"' >> /etc/default/tailscaled; fi
systemctl enable --now tailscaled >/dev/null 2>&1 || true
systemctl restart tailscaled

say "Installing Lumen"
id lumen >/dev/null 2>&1 || useradd --system --home /var/lib/lumen --shell /usr/sbin/nologin lumen
mkdir -p /var/lib/lumen && chown lumen: /var/lib/lumen && chmod 700 /var/lib/lumen
if [ -d /opt/lumen/.git ]; then git -C /opt/lumen pull --ff-only; else git clone --depth 1 "$REPO" /opt/lumen; fi
cd /opt/lumen/server
if ! npm ci --omit=dev >/dev/null 2>&1; then npm install --omit=dev >/dev/null; fi
cd /

cat > /etc/systemd/system/lumen.service <<UNIT
[Unit]
Description=Lumen assistant server
After=network-online.target tailscaled.service
Wants=network-online.target

[Service]
User=lumen
WorkingDirectory=/opt/lumen/server
Environment=PORT=8080 LUMEN_DATA=/var/lib/lumen NODE_ENV=production
EnvironmentFile=-/etc/lumen.env
ExecStart=/usr/bin/node server.mjs
Restart=always
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/lumen
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

# once Tailscale is signed in, publish Lumen on the tailnet over HTTPS (https://<name>.<tailnet>.ts.net)
cat > /etc/systemd/system/lumen-serve.service <<'UNIT'
[Unit]
Description=Publish Lumen on the tailnet
After=tailscaled.service lumen.service
[Service]
Type=oneshot
ExecStart=/bin/sh -c 'until tailscale status --json 2>/dev/null | grep -q "\"BackendState\": \"Running\""; do sleep 5; done; tailscale serve --bg --https=443 http://127.0.0.2:8080'
[Install]
WantedBy=multi-user.target
UNIT
touch /etc/lumen.env && chmod 600 /etc/lumen.env
systemctl daemon-reload
systemctl enable --now lumen >/dev/null
systemctl enable lumen-serve >/dev/null && systemctl start --no-block lumen-serve

# start the Tailscale sign-in; the link appears in the setup dashboard
if [ -n "${TS_AUTHKEY:-}" ]; then tailscale up --hostname="$HOSTNAME_TS" --authkey="$TS_AUTHKEY" || true
else (tailscale up --hostname="$HOSTNAME_TS" --timeout=3s >/dev/null 2>&1 || true); fi

IP=$(hostname -I | awk '{print $1}')
cat <<MSG

  ┌───────────────────────────────────────────────────────────┐
     Lumen is running. Finish setup in your browser:
     http://$IP:8080/setup
  └───────────────────────────────────────────────────────────┘

MSG
