#!/usr/bin/env bash
# One-time preparation of the VM (Ubuntu 24.04) for Openship + the Mado hub.
# Run on the VM as root:  sudo bash vm-setup.sh
# Does: Docker CE from Docker's apt repo, joscha into the docker group, ufw rules
# for the hub ports, and prints the Tailscale login URL. Nothing else.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run with sudo"; exit 1; }
USER_NAME=${USER_NAME:-${SUDO_USER:-$(logname)}}
LAN=${LAN:-192.168.1.0/24}
TAILNET=100.64.0.0/10

echo "== Docker CE"
if ! command -v docker >/dev/null; then
  apt-get update -q
  apt-get install -y -q ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker
usermod -aG docker "$USER_NAME"
docker --version; docker compose version

echo "== Firewall (ufw): hub API/MCP from the tailnet only, frame port from the LAN only"
if command -v ufw >/dev/null; then
  ufw status | head -1
  ufw allow from "$TAILNET" to any port 7878 proto tcp comment 'mado hub api/mcp (tailnet)'
  ufw allow from "$LAN"     to any port 7879 proto tcp comment 'mado frame socket (lan)'
  # Openship dashboard (3001) and API (4000): tailnet only. 80/443 are its edge; leave closed
  # unless you give apps public domains.
  ufw allow from "$TAILNET" to any port 3001 proto tcp comment 'openship dashboard (tailnet)'
  ufw allow from "$TAILNET" to any port 4000 proto tcp comment 'openship api (tailnet)'
  ufw status numbered | grep -E "7878|7879|3001|4000" || true
fi

echo "== Tailscale"
tailscale version | head -1
echo "Now log the VM into the tailnet (opens a URL to visit from any browser):"
tailscale up --ssh=false --hostname="$(hostname)" || true

echo "== done. Log out and back in (or run: newgrp docker) so $USER_NAME can use docker without sudo."
