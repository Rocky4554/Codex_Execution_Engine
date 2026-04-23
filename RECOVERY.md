# Codex Execution Engine — Disaster Recovery

How to rebuild the executor host from scratch if the current EC2 instance dies,
gets terminated, or you want to spin up a replica in another region.

---

## 0. What you need before starting

| Item | Where to get it |
|---|---|
| AWS account with EC2 + EBS access | Your AWS console |
| `koder.pem` SSH key (or generate a new one) | EC2 → Key Pairs |
| `EXECUTOR_AGENT_TOKEN` (long random hex) | Generate: `openssl rand -hex 32` — must match the same token in the **main backend** env (`EXECUTOR_AGENT_TOKEN` on Render) |
| This `Codex_execution_engine` directory | Already in your repo |

---

## 1. Launch a new EC2 instance

| Setting | Value |
|---|---|
| AMI | Ubuntu Server 24.04 LTS (HVM, SSD) |
| Instance type | `t3.small` (recommended) — 2 GB RAM. `t3.micro` works but is tight. |
| Storage | **16 GiB gp3** root volume (DON'T use the 8 GiB default — Docker images alone need ~2 GB) |
| Security group | Inbound: TCP `22` from your IP, TCP `8081` from `0.0.0.0/0` (token-secured) |
| Key pair | `koder` (or whatever you have in `C:\keys\`) |
| IAM role | None needed for runtime. Optional: attach a role with `ec2:ModifyVolume`, `ec2:DescribeVolumes` if you want to resize from inside the box later. |

After it boots, note the **public IPv4** — that's the new value for
`EXECUTOR_AGENT_BASE_URL=http://<new-ip>` in the backend's environment.

---

## 2. Push this directory to the box

From your laptop (PowerShell):

```powershell
# Replace <NEW_IP> with the instance's public IP
$IP = "<NEW_IP>"
scp -i C:\keys\koder.pem -r "E:\Personal Projects\Codex\Codex_execution_engine" ubuntu@${IP}:~/codex-execution
```

(Or `git clone` the repo on the box if it's a public repo.)

---

## 3. Create the `.env` file on the box

```bash
ssh -i C:\keys\koder.pem ubuntu@<NEW_IP>
cd ~/codex-execution
cp .env.example .env
nano .env       # paste EXECUTOR_AGENT_TOKEN value
```

The token MUST be the same one configured in the main backend's
`EXECUTOR_AGENT_TOKEN` env var (Render dashboard).

---

## 4. Run the one-shot bootstrap

```bash
chmod +x setup-host.sh docker/executors/build-all.sh codex-cleanup*.sh
./setup-host.sh
```

This will, idempotently:

1. Install Docker
2. Build `codex-cpp`, `codex-java`, `codex-python`, `codex-javascript`
   (with the `codex.keep=true` label that protects them from cleanup)
3. Pull and start `codex-executor-agent` via `docker-compose.ec2.yml`
4. Install both cleanup scripts to `~/` and register cron
5. Disable `unattended-upgrades` + `apt-daily*` timers (they cause apt
   lock deadlocks when disk fills)
6. Print final disk + container state

Expected end state: HTTP 401 from `/actuator/health` (auth required → service alive),
all 5 `codex-*` images present, two cron jobs registered.

---

## 5. Update the main backend

In Render → backend service → Environment, change:

```
EXECUTOR_AGENT_BASE_URL=http://<NEW_IP>
```

(Token stays the same if you reused it.) Render will redeploy automatically.

---

## 6. Smoke test

From the backend, submit a Java + Python + C++ + JS solution. All four
should return ACCEPTED / WRONG_ANSWER (not RUNTIME_ERROR / connection error).

You can also tail the executor agent logs:

```bash
docker logs -f codex-executor-agent
```

---

## What's in this directory

| File | Purpose |
|---|---|
| `Dockerfile` | Builds the executor agent JAR (Spring Boot, port 8081) |
| `pom.xml` / `src/` | Executor agent Java source |
| `docker-compose.ec2.yml` | Runs the agent container, mounts Docker socket + `/tmp/codex` |
| `.env.example` | Template for `EXECUTOR_AGENT_TOKEN` |
| `docker/executors/<lang>/Dockerfile` | The four sandbox images each submission runs in |
| `docker/executors/build-all.sh` | Builds all four sandbox images (with `codex.keep=true` label) |
| `codex-cleanup.sh` | Hourly heavy cleanup. Removes ALL non-codex images, volumes, build cache. Truncates >50 MB container logs. Trims journal + apt cache. |
| `codex-cleanup-light.sh` | 5-minute lightweight cleanup. Stopped containers + dangling layers only. Safe between submissions. |
| `setup-host.sh` | One-shot bootstrap (calls `build-all.sh`, sets up cron, disables apt timers) |
| `setup-memory-cleanup.sh` | **Legacy** — superseded by `setup-host.sh`. Kept for reference only. |
| `MEMORY_OPTIMIZATION.md` | Notes on JVM heap sizing for the agent on small instances |
| `test-all-languages.sh`, `test-twosum.py` | Smoke tests |

---

## Resizing the EBS volume later

If `df -h /` shows the disk filling up:

1. AWS Console → EC2 → Volumes → select the root volume → **Actions → Modify volume**
2. Set new Size (GiB) and click Modify
3. Wait ~30 s for state to be `optimizing`, then SSH in and run:

   ```bash
   sudo growpart /dev/nvme0n1 1
   sudo resize2fs /dev/root
   df -h /
   ```

No reboot needed; the executor keeps running through the resize.

---

## What NOT to do

- **Don't** add `docker image prune -a` (or `-af`) without the `codex.keep=true`
  filter — it will nuke your sandbox images mid-day. The provided
  `codex-cleanup.sh` v3 does this safely.
- **Don't** re-enable `unattended-upgrades` on a tight-disk instance.
  It will deadlock on apt locks the moment the disk hits 100%.
- **Don't** edit the language Dockerfiles to drop the
  `LABEL codex.keep=true` line.
