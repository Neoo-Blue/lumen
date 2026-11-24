#!/usr/bin/env bash
# Lumen with Docker:  curl -fsSL https://raw.githubusercontent.com/Neoo-Blue/lumen/main/deploy/install-docker.sh | bash
set -euo pipefail
REPO="${LUMEN_REPO:-https://github.com/Neoo-Blue/lumen.git}"
DIR="${LUMEN_DIR:-$HOME/lumen}"
say() { printf '\033[1m%s\033[0m\n' "$*"; }

command -v docker >/dev/null || { say "Docker is not installed. Get it from https://docs.docker.com/engine/install/ and run this again."; exit 1; }
docker compose version >/dev/null 2>&1 || { say "Docker Compose v2 is missing (the 'docker compose' command). Update Docker and run this again."; exit 1; }
command -v git >/dev/null || { say "git is not installed. Install it (e.g. sudo apt install git) and run this again."; exit 1; }

if [ -d "$DIR/.git" ]; then say "Updating $DIR"; git -C "$DIR" pull --ff-only; else say "Downloading Lumen to $DIR"; git clone --depth 1 "$REPO" "$DIR"; fi
cd "$DIR/deploy"
[ -f .env ] || cp .env.example .env
say "Starting Lumen and Tailscale (the first start builds the image, a few minutes)"
docker compose up -d --build

IP=$(hostname -I 2>/dev/null | awk '{print $1}'); IP=${IP:-localhost}
PORT=$(grep -E '^LUMEN_SETUP_PORT=' .env | cut -d= -f2); PORT=${PORT:-8080}
cat <<MSG

  ┌───────────────────────────────────────────────────────────┐
     Lumen is running. Finish setup in your browser:
     http://$IP:$PORT/setup
  └───────────────────────────────────────────────────────────┘

MSG
