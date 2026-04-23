#!/bin/bash
# Codex Execution Engine - Automatic Cleanup (v3, hourly)
#
# Frees disk + RAM each hour. PROTECTS:
#   - Any image whose repo name starts with "codex-"
#   - Any image carrying the label "codex.keep=true"
# (belt + suspenders — either alone is enough.)
#
# Install via cron:
#   0 * * * * /home/ubuntu/codex-cleanup.sh >> /home/ubuntu/codex-cleanup.log 2>&1
set -u
TS="[$(date)]"
echo "$TS Running Codex cleanup..."

# Stopped containers
docker container prune -f

# Build the safe-list: codex-* OR label codex.keep=true
PROTECTED_IDS=$(docker images --format '{{.Repository}} {{.ID}}' | awk '/^codex-/{print $2}' | sort -u)
KEEP_IDS=$(docker images --filter "label=codex.keep=true" -q | sort -u)
SAFE_IDS=$(echo -e "$PROTECTED_IDS\n$KEEP_IDS" | sort -u)

# Remove everything not in the safe list
for id in $(docker images -q | sort -u); do
    if ! echo "$SAFE_IDS" | grep -q "^$id$"; then
        docker rmi -f "$id" >/dev/null 2>&1 || true
    fi
done

# Final dangling sweep + volumes + builder cache
docker image prune -f >/dev/null 2>&1
docker volume prune -f >/dev/null 2>&1
docker builder prune -af >/dev/null 2>&1

# Truncate big container json logs
sudo find /var/lib/docker/containers -name '*-json.log' -size +50M -exec truncate -s 0 {} \; 2>/dev/null

# Trim journal & apt cache
sudo journalctl --vacuum-size=50M >/dev/null 2>&1
sudo apt-get clean >/dev/null 2>&1

# Drop FS caches
sync && echo 3 | sudo tee /proc/sys/vm/drop_caches >/dev/null

KEPT=$(docker images --format '{{.Repository}}:{{.Tag}}' | sort -u | tr '\n' ' ')
echo "$TS Cleanup done. Disk free: $(df -h / | tail -1 | awk '{print $4}')  RAM avail: $(free -m | awk '/Mem/{print $7}')MB"
echo "$TS Kept images: $KEPT"
