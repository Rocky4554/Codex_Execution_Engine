# Codex Execution Engine — Troubleshooting Guide

All bugs hit during real deployment, with root causes and fixes.

---

## C++ Compilation Always Fails

### Symptom
Every C++ submission returns `COMPILATION_ERROR`. EC2 logs show:
```
Compilation failed for submission ... (120051ms)
```
or
```
g++: fatal error: Killed signal terminated program cc1plus
compilation terminated.
```

### Root Cause 1 — Container dies at 120 seconds
The container runs `sleep 120` as its main process. Any `docker exec` command (including compilation) is killed when the container stops at 120 seconds.

**Fix:** Raise `sleep` to 600 in `DockerExecutor.java`:
```java
.withCmd("sleep", "600")
```

### Root Cause 2 — Memory limit OOM-kills the compiler
The container was created with `memoryLimitMb` taken directly from the problem (e.g. 128 MB). `g++` compiling `#include<bits/stdc++.h>` needs 300–500 MB. The Linux OOM killer sends SIGKILL to `cc1plus`.

**Fix:** Use a 512 MB floor in `DockerExecutor.java`:
```java
.withMemory((long) Math.max(512, memoryLimitMb) * 1024 * 1024)
.withMemorySwap((long) Math.max(512, memoryLimitMb) * 1024 * 1024)
```

### Root Cause 3 — Precompiled header (PCH) not being used
The C++ Docker image builds a PCH with `-std=c++17`. The Language record in the database stored the compile command as `g++ -std=c++11 -o solution solution.cpp`. GCC only uses a PCH when the language standard matches exactly — so the c++17 PCH was silently ignored and every submission had to parse thousands of headers from scratch.

**Fix:** Update the compile command to c++17 (`DataInitializer.synchronizeLanguages()` in the backend does this automatically on next startup):
```
g++ -std=c++17 -o solution solution.cpp
```

After all three fixes, C++ compilation takes ~2 seconds instead of 120+ seconds.

---

## 503: Executor Saturated

### Symptom
Render backend logs show:
```
Executor agent returned 503 ({"error":"executor saturated, retry later"}), retrying once
```

### Root Cause
`EXECUTOR_AGENT_MAX_CONCURRENT=1` in EC2's `.env`. Only one submission can run at a time. When C++ compilation was taking 120 seconds, any second request during that window was rejected.

**Fix (immediate):** The compilation fix above (Root Causes 1–3) reduces execution time to 2–5 seconds, so saturation almost never occurs with `MAX_CONCURRENT=1`.

**Fix (if still saturated):** Increase concurrency on EC2:
```bash
nano ~/codex-execution/.env
# Change to:
EXECUTOR_AGENT_MAX_CONCURRENT=2

cd ~/codex-execution
sudo docker compose -f docker-compose.ec2.yml --env-file .env up -d
```
> On a t2.micro (1 GB RAM), keep this at 1 or 2 max to avoid OOM at the host level.

---

## `application/octet-stream` Deserialization Error

### Symptom
Render logs show:
```
ERROR ExecutorAgentClient: Executor agent transient error after retry:
Error while extracting response for type [ExecuteResponse] and content type [application/octet-stream]
```

### Root Cause
The backend's `RestClient` retried a 503 response. The retry went through, but by that time the executor was processing another request — it returned a Spring error response with an unexpected content type.

This error goes away entirely once C++ compilation is fast (Root Causes 1–3 fixed), because the semaphore is no longer held for 120 seconds.

---

## Redis `BeanCreationException` on Render

### Symptom
Backend fails to start on Render with:
```
BeanCreationException: Error creating bean with name 'redissonClient'
```

### Root Cause
The old Redis Labs instance was deleted. The `REDIS_URL` in Render's environment variables still pointed to the deleted host, which no longer resolved.

**Fix:** Update `REDIS_URL` and `REDIS_PASSWORD` in Render → Environment to the new Redis Cloud instance values, then redeploy.

---

## `Connect timed out` from Render to EC2

### Symptom
Render logs show:
```
I/O error on POST request for "http://3.110.161.110:8081/v1/execute": Connect timed out
```

### Root Cause
AWS Security Group on the EC2 instance did not have an inbound rule for port 8081.

**Fix:**
1. AWS Console → EC2 → Security Groups → the group attached to your instance
2. Inbound rules → Edit → Add rule:
   - Type: Custom TCP
   - Port range: 8081
   - Source: 0.0.0.0/0

---

## `docker-compose-plugin` Not Found on Ubuntu 24.04

### Symptom
`setup-host.sh` fails with:
```
E: Package 'docker-compose-plugin' has no installation candidate
```

### Root Cause
`setup-host.sh` ran `apt-get install docker-compose-plugin` without first adding Docker's official apt repository. Ubuntu 24.04's default repos don't include this package.

**Fix (already applied in `setup-host.sh`):** Add Docker's apt repository before installing:
```bash
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

---

## `ghcr.io` Image Pull Denied

### Symptom
`docker compose up` fails with:
```
pull access denied for ghcr.io/rocky4554/codex_executor_agent
```

### Root Cause
The original `docker-compose.ec2.yml` tried to pull a pre-built image from GitHub Container Registry. That image was never pushed there.

**Fix (already applied in `docker-compose.ec2.yml`):** Build the image locally instead:
```yaml
services:
  codex-executor-agent:
    build:
      context: .
      dockerfile: Dockerfile
```

---

## `git pull` Blocked by Local Changes

### Symptom
```
error: Your local changes to the following files would be overwritten by merge:
        setup-host.sh
```

### Fix
```bash
git checkout -- setup-host.sh   # discard local changes
git pull
chmod +x setup-host.sh          # re-add execute permission (git checkout resets it)
```

---

## Java: `class Main is public, should be declared in a file named Main.java`

### Symptom
Java submission returns `COMPILATION_ERROR` with:
```
solution.java:3: error: class Main is public, should be declared in a file named Main.java
```

### Root Cause
The Java compile command is `javac solution.java`, so the file is named `solution.java`. But the public class inside is named `Main`. Java requires the filename to match the public class name.

**Fix:** Users writing Java must either:
- Name the public class `solution` (matching the filename), or
- The compile command must save the file as `Main.java` — update the Language `fileExtension` if needed.

---

## SSH: `Warning: Identity file not accessible` (Running SSH from Inside EC2)

### Symptom
```
Warning: Identity file C:\keys\code_execution_engine.pem not accessible: No such file or directory.
Host key verification failed.
```

### Root Cause
You were already logged into EC2 (`ubuntu@ip-172-31-40-21`) and tried to SSH again using a Windows path. The Windows path `C:\keys\...` doesn't exist on Linux.

**Fix:** You're already on EC2. Just run the commands directly — no SSH needed:
```bash
cd ~/codex-execution
git pull
sudo docker compose -f docker-compose.ec2.yml --env-file .env up -d --build
```

---

## `ObjectOptimisticLockingFailureException` on Local Startup

### Symptom
Local Spring Boot startup logs show:
```
ObjectOptimisticLockingFailureException: Row was updated or deleted by another transaction
```

### Root Cause
`CuratedProblemCatalogInitializer` (or `DataInitializer`) tries to update the same database rows that the live Render backend is simultaneously modifying. Both dev and prod point to the same Supabase database.

**This is not a code bug.** It's a dev/prod database collision — expected when running locally against the shared cloud database.

**Fix (not required):** For a clean local dev setup, point `spring.datasource.url` to a local PostgreSQL instance instead of Supabase.

---

## `mvn spring-boot:run` Not Recognized

### Symptom
```
'mvn:spring-boot:run' is not recognized as an internal or external command
```

### Root Cause
Typed `mvn:spring-boot:run` with a colon before `spring-boot` instead of a space.

**Fix:**
```bash
mvn spring-boot:run
```
Note: Run this from the `Codex_code/` directory (where `pom.xml` lives).
