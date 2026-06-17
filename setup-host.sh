#!/usr/bin/env bash
# Codex Execution Engine - One-shot host bootstrap (Ubuntu 22.04 / 24.04 on EC2).
#
# What this script does (idempotent — safe to re-run):
#   1. Installs Docker if missing
#   2. Builds the four language sandbox images (codex-cpp/java/python/javascript)
#      with codex.keep=true label
#   3. Starts the executor agent via docker-compose.ec2.yml (requires .env)
#   4. Installs codex-cleanup.sh + codex-cleanup-light.sh into ~/ and registers cron
#   5. Disables unattended-upgrades + apt-daily timers (they cause lock deadlocks
#      on full disks)
#   6. Prints disk + container state
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

# ── 1. Docker ────────────────────────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
    echo "[1/6] Installing Docker (official repo)..."
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
    echo "[1/6] Docker already installed: $(docker --version)"
fi

# ── 2. Build language images ─────────────────────────────────────
echo
echo "[2/6] Building language sandbox images..."
bash "$REPO_DIR/docker/executors/build-all.sh"

# ── 3. Executor agent via compose ────────────────────────────────
echo
echo "[3/6] Starting executor agent..."
if [ ! -f "$REPO_DIR/.env" ]; then
    echo "    .env missing — copy from .env.example and set EXECUTOR_AGENT_TOKEN, then re-run."
    exit 1
fi
sudo docker compose -f "$REPO_DIR/docker-compose.ec2.yml" --env-file "$REPO_DIR/.env" up -d --pull always
sudo docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'

# ── 4. Cleanup scripts + cron ───────────────────────────────────
echo
echo "[4/6] Installing cleanup scripts and cron..."
install -m 0755 "$REPO_DIR/codex-cleanup.sh"        "$HOME_DIR/codex-cleanup.sh"
install -m 0755 "$REPO_DIR/codex-cleanup-light.sh"  "$HOME_DIR/codex-cleanup-light.sh"

CRON_LIGHT="*/5 * * * * $HOME_DIR/codex-cleanup-light.sh >> $HOME_DIR/codex-cleanup.log 2>&1"
CRON_HEAVY="0 * * * * $HOME_DIR/codex-cleanup.sh >> $HOME_DIR/codex-cleanup.log 2>&1"
( crontab -l 2>/dev/null | grep -v 'codex-cleanup' ; \
  echo "$CRON_LIGHT" ; \
  echo "$CRON_HEAVY" ) | crontab -
echo "    Cron now:"
crontab -l | sed 's/^/        /'

# ── 5. Disable runaway apt timers ───────────────────────────────
echo
echo "[5/6] Disabling unattended-upgrades + apt-daily timers..."
sudo systemctl disable --now unattended-upgrades   2>/dev/null || true
sudo systemctl disable --now apt-daily.timer       2>/dev/null || true
sudo systemctl disable --now apt-daily-upgrade.timer 2>/dev/null || true
sudo snap set system refresh.retain=2 2>/dev/null || true

# ── 6. Final state ──────────────────────────────────────────────
echo
echo "[6/6] Done. Final state:"
df -h /
echo
docker images
echo
echo "Health check (expects HTTP 401 because the endpoint requires the bearer token):"
curl -s -o /dev/null -w '    /actuator/health -> HTTP %{http_code}\n' http://localhost:8081/actuator/health || true
echo
echo "Bootstrap complete."
