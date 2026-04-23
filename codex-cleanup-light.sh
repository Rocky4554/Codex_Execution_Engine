#!/bin/bash
# Codex Execution Engine - Lightweight Cleanup (every 5 min)
#
# Cheap sweep that runs between submissions. Only removes stopped containers
# and TRULY dangling layers — NEVER touches tagged images. Safe to run while
# the executor agent is processing requests.
#
# Install via cron:
#   */5 * * * * /home/ubuntu/codex-cleanup-light.sh >> /home/ubuntu/codex-cleanup.log 2>&1
set -u

docker container prune -f >/dev/null 2>&1
docker image prune -f >/dev/null 2>&1   # dangling-only by design (no -a)

sudo find /var/lib/docker/containers -name '*-json.log' -size +25M \
    -exec truncate -s 0 {} \; 2>/dev/null

# Drop pagecache only (cheap)
sync && echo 1 | sudo tee /proc/sys/vm/drop_caches >/dev/null
