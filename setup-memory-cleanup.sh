#!/bin/bash
# Setup automatic memory cleanup on EC2

set -e

echo "Setting up automatic memory cleanup..."

# Copy cleanup script to standard location
sudo cp cleanup-memory.sh /usr/local/bin/codex-cleanup-memory
sudo chmod +x /usr/local/bin/codex-cleanup-memory

# Add to cron to run every 15 minutes
CRON_JOB="*/15 * * * * /usr/local/bin/codex-cleanup-memory >> /var/log/codex-cleanup.log 2>&1"

# Check if already exists
if sudo crontab -l 2>/dev/null | grep -q "codex-cleanup-memory"; then
    echo "✅ Cron job already exists"
else
    echo "Adding cron job..."
    (sudo crontab -l 2>/dev/null || echo "") | grep -v "codex-cleanup-memory" | sudo tee /tmp/crontab.tmp >/dev/null
    echo "$CRON_JOB" | sudo tee -a /tmp/crontab.tmp >/dev/null
    sudo crontab /tmp/crontab.tmp
    rm /tmp/crontab.tmp
    echo "✅ Cron job added (runs every 15 minutes)"
fi

# Also setup daily restart of containers (frees memory completely)
RESTART_JOB="0 3 * * * docker-compose -f /home/ubuntu/Codex_code/docker-compose.prod.yml restart >> /var/log/codex-restart.log 2>&1"

if sudo crontab -l 2>/dev/null | grep -q "docker-compose.*restart"; then
    echo "✅ Restart job already exists"
else
    echo "Adding daily restart job (3 AM UTC)..."
    (sudo crontab -l 2>/dev/null || echo "") | grep -v "docker-compose.*restart" | sudo tee /tmp/crontab.tmp >/dev/null
    echo "$RESTART_JOB" | sudo tee -a /tmp/crontab.tmp >/dev/null
    sudo crontab /tmp/crontab.tmp
    rm /tmp/crontab.tmp
    echo "✅ Daily restart scheduled at 3 AM UTC"
fi

echo ""
echo "Setup complete! Memory will be cleaned every 15 minutes."
echo "Containers will restart daily at 3 AM UTC to free all memory."
