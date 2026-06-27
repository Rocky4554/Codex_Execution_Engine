#!/usr/bin/env bash
# Codex Execution Engine - One-shot host bootstrap (Ubuntu 22.04 / 24.04 on EC2).
#
# What this script does (idempotent — safe to re-run):
#   1. Ensures a 2 GB swapfile exists + low swappiness (prevents OOM on the 1 GB
#      box; needed to run nginx/Grafana/Loki alongside the executor agent)
#   2. Installs Docker if missing
#   3. Builds the four language sandbox images (codex-cpp/java/python/javascript)
#      with codex.keep=true label
#   4. Starts the executor agent via docker-compose.ec2.yml (requires .env)
#   5. Installs codex-cleanup.sh + codex-cleanup-light.sh into ~/ and registers cron
#   6. Disables unattended-upgrades + apt-daily timers (they cause lock deadlocks
#      on full disks)
#   7. Prints disk + container state
#
# Prereqs:
#   - This repo cloned to ~/codex-execution
#   - .env file present alongside docker-compose.ec2.yml with EXECUTOR_AGENT_TOKEN
#   - sudo access
#
# Usage:
#   cd ~/codex-execution && ./setup-host.sh

set -e

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOME_DIR="${HOME}"

echo "============================================================"
echo " Codex Execution Engine - host bootstrap"
echo "============================================================"
echo " Repo dir : $REPO_DIR"
echo " Home dir : $HOME_DIR"
echo

# ── 1. Swapfile ──────────────────────────────────────────────────
# The free-tier box has ~1 GB RAM and no swap by default. Without swap the
# kernel OOM-kills containers under load. A 2 GB swapfile gives enough headroom
# to run the executor agent plus light monitoring (nginx/Grafana/Loki).
SWAP_SIZE_GB="${SWAP_SIZE_GB:-2}"
if ! swapon --show 2>/dev/null | grep -q '/swapfile'; then
    echo "[1/7] Creating ${SWAP_SIZE_GB}G swapfile..."
    sudo fallocate -l "${SWAP_SIZE_GB}G" /swapfile \
        || sudo dd if=/dev/zero of=/swapfile bs=1M count="$((SWAP_SIZE_GB*1024))"
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
    echo "    Swapfile active and registered in /etc/fstab."
else
    echo "[1/7] Swapfile already active: $(swapon --show=NAME,SIZE --noheadings | tr '\n' ' ')"
fi
# Prefer RAM, only spill to swap under real pressure.
sudo sysctl -q vm.swappiness=10
grep -q '^vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf >/dev/null

# ── 2. Docker ────────────────────────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
    echo "[2/7] Installing Docker (official repo)..."
    sudo apt-get update -qq
    sudo apt-get install -y -qq ca-certificates curl gnupg
    sudo install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    sudo chmod a+r /etc/apt/keyrings/docker.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
        | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
    sudo apt-get update -qq
    sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    sudo systemctl enable --now docker
    sudo usermod -aG docker "$USER"
    echo "    Docker installed. NOTE: re-login (or run 'newgrp docker') to use docker without sudo."
else
    echo "[2/7] Docker already installed: $(docker --version)"
fi

# ── 3. Build language images ─────────────────────────────────────
echo
echo "[3/7] Building language sandbox images..."
bash "$REPO_DIR/docker/executors/build-all.sh"

# ── 4. Executor agent via compose ────────────────────────────────
echo
echo "[4/7] Starting executor agent..."
if [ ! -f "$REPO_DIR/.env" ]; then
    echo "    .env missing — copy from .env.example and set EXECUTOR_AGENT_TOKEN, then re-run."
    exit 1
fi
sudo docker compose -f "$REPO_DIR/docker-compose.ec2.yml" --env-file "$REPO_DIR/.env" up -d --build
sudo docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

# ── 5. Cleanup scripts + cron ───────────────────────────────────
echo
echo "[5/7] Installing cleanup scripts and cron..."
install -m 0755 "$REPO_DIR/codex-cleanup.sh"        "$HOME_DIR/codex-cleanup.sh"
install -m 0755 "$REPO_DIR/codex-cleanup-light.sh"  "$HOME_DIR/codex-cleanup-light.sh"

CRON_LIGHT="*/5 * * * * $HOME_DIR/codex-cleanup-light.sh >> $HOME_DIR/codex-cleanup.log 2>&1"
CRON_HEAVY="0 * * * * $HOME_DIR/codex-cleanup.sh >> $HOME_DIR/codex-cleanup.log 2>&1"
( crontab -l 2>/dev/null | grep -v 'codex-cleanup' ; \
  echo "$CRON_LIGHT" ; \
  echo "$CRON_HEAVY" ) | crontab -
echo "    Cron now:"
crontab -l | sed 's/^/        /'

# ── 6. Disable runaway apt timers ───────────────────────────────
echo
echo "[6/7] Disabling unattended-upgrades + apt-daily timers..."
sudo systemctl disable --now unattended-upgrades   2>/dev/null || true
sudo systemctl disable --now apt-daily.timer       2>/dev/null || true
sudo systemctl disable --now apt-daily-upgrade.timer 2>/dev/null || true
sudo snap set system refresh.retain=2 2>/dev/null || true

# ── 7. Final state ──────────────────────────────────────────────
echo
echo "[7/7] Done. Final state:"
df -h /
echo
free -h
echo
docker images
echo
echo "Health check (expects HTTP 401 because the endpoint requires the bearer token):"
curl -s -o /dev/null -w '    /actuator/health -> HTTP %{http_code}\n' http://localhost:8081/actuator/health || true
echo
echo "Bootstrap complete."
