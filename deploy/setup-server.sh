#!/usr/bin/env bash
# One-time bootstrap for a fresh Ubuntu 24.04 VPS (run as root).
# Installs Docker, locks down the firewall, and lays out /opt/kol.
# Safe to re-run; every step is idempotent.
set -euo pipefail

echo "== apt update + base packages =="
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get -y upgrade
apt-get -y install ufw fail2ban curl ca-certificates unattended-upgrades

echo "== Docker =="
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker

echo "== Firewall (deny by default; SSH + web + game only) =="
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp    # SSH
ufw allow 80/tcp    # HTTP (Caddy -> redirects/ACME)
ufw allow 443/tcp   # HTTPS (site + forum)
ufw allow 43594/tcp # game server
# ufw allow 8228/tcp  # client bootstrap server — enable when it moves to this box
ufw --force enable

echo "== fail2ban (SSH brute-force protection) =="
systemctl enable --now fail2ban

echo "== Directory layout =="
mkdir -p /opt/kol/runtime/cache /opt/kol/runtime/rsa /opt/kol/runtime/saves /opt/kol/backups

# The deploy user (OVH images: "ubuntu") owns /opt/kol and can drive docker.
DEPLOY_USER="${SUDO_USER:-ubuntu}"
if id "$DEPLOY_USER" >/dev/null 2>&1; then
  usermod -aG docker "$DEPLOY_USER"
  chown -R "$DEPLOY_USER":"$DEPLOY_USER" /opt/kol
fi

echo "== Backup cron (nightly 07:10 UTC) =="
cat > /etc/cron.d/kol-backup <<'CRON'
10 7 * * * root /opt/kol/backup.sh >> /var/log/kol-backup.log 2>&1
CRON
chmod 644 /etc/cron.d/kol-backup

echo
echo "Bootstrap complete. Remaining manual steps (see deploy/README.md):"
echo "  1. docker login ghcr.io -u <github-user>   (PAT with read:packages)"
echo "  2. Create /opt/kol/.env from deploy/README.md's template"
echo "  3. Upload runtime files: cache/, rsa/key.pem, xteas.json, game.yml, dev-settings.yml"
echo "  4. Copy deploy/backup.sh to /opt/kol/backup.sh and chmod +x it"
echo "  5. Add the GitHub Actions deploy key to ~/.ssh/authorized_keys"
