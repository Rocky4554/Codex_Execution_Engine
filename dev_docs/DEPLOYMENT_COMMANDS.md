# Codex Execution Engine — Deployment Commands

All commands used to deploy the Codex Execution Engine to a blank AWS EC2 instance, with explanations.

---

## On Your Local Machine (Windows)

### SSH into EC2
```
ssh -i C:\keys\code_execution_engine.pem ubuntu@3.110.161.110
```
| Part | Meaning |
|---|---|
| `ssh` | Secure Shell — opens a remote terminal session |
| `-i C:\keys\code_execution_engine.pem` | Use this private key file for authentication |
| `ubuntu@3.110.161.110` | Login as user `ubuntu` on EC2 IP `3.110.161.110` |

---

## On EC2 (after SSH in)

### Clone the repository
```bash
git clone https://github.com/Rocky4554/Codex_Execution_Engine.git ~/codex-execution
```
Downloads the project from GitHub into a folder called `codex-execution` in the home directory.

```bash
cd ~/codex-execution
```
Move into the project directory so all subsequent commands run from there.

---

### Generate a secret token and create `.env`
```bash
TOKEN=$(openssl rand -hex 32)
```
Generates a 32-byte random hex string (64 characters) and stores it in the shell variable `TOKEN`. This is the shared secret used to authenticate requests between the backend and this executor agent.

```bash
echo "EXECUTOR_AGENT_TOKEN=$TOKEN" > .env
```
Creates the `.env` file and writes the token into it. The `>` overwrites the file if it exists.

```bash
echo "EXECUTOR_AGENT_MAX_CONCURRENT=1" >> .env
```
Appends the concurrency limit to `.env`. `>>` appends instead of overwriting. Value `1` means only one code submission runs at a time (safe for small EC2).

```bash
cat .env
```
Prints the `.env` file contents to verify it was written correctly. **Copy the token value** — you need it in your backend's environment variables.

---

### Make scripts executable
```bash
chmod +x setup-host.sh docker/executors/build-all.sh codex-cleanup.sh codex-cleanup-light.sh
```
`chmod +x` adds execute permission to the listed files. Without this, running `./setup-host.sh` gives a "Permission denied" error. Git does not always preserve execute bits when cloning on Linux.

---

### Run the bootstrap script
```bash
./setup-host.sh
```
One-shot setup script that does everything:
1. Creates a 2 GB swapfile + sets `vm.swappiness=10` (prevents OOM on the 1 GB box)
2. Adds Docker's official apt repository and installs Docker CE + docker-compose-plugin
3. Builds the 4 language sandbox images (`codex-cpp`, `codex-java`, `codex-python`, `codex-javascript`)
4. Starts the executor agent container on port `8081`
5. Installs cleanup cron jobs (every 5 min light, every 1 hour heavy)
6. Disables `unattended-upgrades` to prevent apt lock deadlocks
7. Prints final disk, memory, and container state

Takes **5–10 minutes** on first run.

> Swap size is configurable: `SWAP_SIZE_GB=4 ./setup-host.sh`. The step is
> idempotent — if `/swapfile` is already active it is left untouched.

---

## Troubleshooting Commands (used during this deployment)

### When `git pull` was blocked by local changes
```bash
git checkout -- setup-host.sh
```
Discards local uncommitted changes to `setup-host.sh` and restores it to the last committed version. Used when `git pull` aborted with "Your local changes would be overwritten by merge."

```bash
git pull
```
Pulls the latest changes from GitHub (origin/main). Fast-forwards the local branch.

---

### Re-apply execute permission after `git checkout`
```bash
chmod +x setup-host.sh
```
`git checkout` restores file content but resets permissions. This re-adds the execute bit so the script can be run again.

---

## Add Swap to an Existing Box (manual)

If a box was set up before swap was added to `setup-host.sh`, add it once by hand.
Fixes OOM kills on the 1 GB instance and frees headroom for nginx/Grafana/Loki.

```bash
ssh -i C:\keys\code_execution_engine.pem ubuntu@3.110.161.110

sudo fallocate -l 2G /swapfile                      # allocate a 2 GB file
sudo chmod 600 /swapfile                            # owner-only (swapon requires this)
sudo mkswap /swapfile                               # format as swap
sudo swapon /swapfile                               # enable now
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab   # persist across reboot
sudo sysctl vm.swappiness=10                        # prefer RAM, swap only under pressure
echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf

free -h          # verify: Swap row shows 2.0Gi
swapon --show    # verify: lists /swapfile
```

| Command | What it does |
|---|---|
| `fallocate -l 2G /swapfile` | Reserves a 2 GB file on disk for swap |
| `chmod 600` | Restricts to owner — `swapon` rejects world-readable swap files |
| `mkswap` | Formats the file as swap space |
| `swapon` | Activates the swap immediately (no reboot) |
| `/etc/fstab` line | Re-enables the swapfile automatically after a reboot |
| `vm.swappiness=10` | Low value → kernel uses RAM first, swap is a fallback |

See `MEMORY_OPTIMIZATION.md` (root) for the full rationale and memory-savings breakdown.

---

## Updating EC2 After a Code Change

When you push new code to GitHub and need to redeploy on EC2:

```bash
# SSH into EC2 first (from your local Windows machine)
ssh -i C:\keys\code_execution_engine.pem ubuntu@3.110.161.110

# On EC2 — pull latest + rebuild the Docker image + restart
cd ~/codex-execution
git pull
sudo docker compose -f docker-compose.ec2.yml --env-file .env up -d --build
```

| Command | What it does |
|---|---|
| `git pull` | Downloads latest commits from GitHub |
| `up -d --build` | Rebuilds the image from the updated Dockerfile/source, then restarts the container in the background |

If `git pull` fails with "local changes would be overwritten":
```bash
git checkout -- <filename>   # discard local changes to that file
git pull
```

---

## After Deployment — Verify It Works

### Check running containers
```bash
docker ps
```
Lists all currently running Docker containers. You should see `codex-executor-agent` with status `Up`.

### Health check
```bash
curl http://localhost:8081/v1/healthz
```
Hits the health endpoint of the executor agent. Returns a JSON response with Docker status, disk space, and concurrency state.

### Watch live logs
```bash
docker logs -f codex-executor-agent
```
Streams live logs from the executor agent container. `-f` follows (like `tail -f`). Useful for debugging submissions.

---

## Update Your Backend (Render)

After deployment, set these two environment variables in your Render backend dashboard:

| Variable | Value |
|---|---|
| `EXECUTOR_AGENT_BASE_URL` | `http://3.110.161.110:8081` |
| `EXECUTOR_AGENT_TOKEN` | *(the token printed by `cat .env` on EC2)* |

Render will redeploy the backend automatically after saving.

---

## Quick Reference — All EC2 Commands in Order

```bash
# 1. Clone
git clone https://github.com/Rocky4554/Codex_Execution_Engine.git ~/codex-execution
cd ~/codex-execution

# 2. Create .env
TOKEN=$(openssl rand -hex 32)
echo "EXECUTOR_AGENT_TOKEN=$TOKEN" > .env
echo "EXECUTOR_AGENT_MAX_CONCURRENT=1" >> .env
cat .env

# 3. Make scripts executable
chmod +x setup-host.sh docker/executors/build-all.sh codex-cleanup.sh codex-cleanup-light.sh

# 4. Bootstrap (installs Docker, builds images, starts agent)
./setup-host.sh

# 5. Verify
docker ps
curl http://localhost:8081/v1/healthz
```
