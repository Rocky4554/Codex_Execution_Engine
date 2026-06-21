# Connecting the Backend to the Codex Execution Engine

How the main backend (`Codex_code`) talks to this EC2 execution engine (`Codex_Execution_Engine`).

---

## How the Connection Works

```
Frontend (React)
      │
      ▼
Backend on Render (Codex_code — Spring Boot)
      │
      │  POST http://<EC2-IP>:8081/v1/execute
      │  Header: Authorization: Bearer <EXECUTOR_AGENT_TOKEN>
      ▼
Execution Engine on EC2 (this project — Spring Boot)
      │
      ▼
Docker container (runs user's code)
      │
      ▼
Returns verdict: ACCEPTED / WRONG_ANSWER / TLE / MLE / RE / CE
```

---

## Variables That Must Match on Both Sides

| Variable | Backend (`Codex_code`) | Execution Engine (EC2) |
|---|---|---|
| **Token** | `EXECUTOR_AGENT_TOKEN` | `EXECUTOR_AGENT_TOKEN` |
| **URL** | `EXECUTOR_AGENT_BASE_URL=http://<EC2-IP>:8081` | (EC2 public IP, port 8081) |

The token **must be identical** on both sides — the execution engine rejects any request where the token doesn't match.

---

## Step 1 — Get the Token from EC2

SSH into EC2 and print the token:

```bash
ssh -i C:\keys\code_execution_engine.pem ubuntu@3.110.161.110
cat ~/codex-execution/.env
```

Output example:
```
EXECUTOR_AGENT_TOKEN=0e57712cd57a8f690b43ebbec3ef5f5fba07fc5c9ddd927afde85d01a17f5699
EXECUTOR_AGENT_MAX_CONCURRENT=1
```

Copy the `EXECUTOR_AGENT_TOKEN` value.

---

## Step 2 — Set Variables in the Backend

### On Render (Production)

Go to **Render → your backend service → Environment** and set:

```
EXECUTOR_AGENT_BASE_URL=http://3.110.161.110:8081
EXECUTOR_AGENT_TOKEN=0e57712cd57a8f690b43ebbec3ef5f5fba07fc5c9ddd927afde85d01a17f5699
EXECUTOR_AGENT_TIMEOUT_MS=90000
```

Render auto-redeploys after saving.

### For Local Development

In `Codex_code/src/main/resources/application-dev.properties`, the defaults are:

```properties
executor.agent.base-url=${EXECUTOR_AGENT_BASE_URL:http://3.110.161.110:8081}
executor.agent.token=${EXECUTOR_AGENT_TOKEN:0e57712cd57a8f690b43ebbec3ef5f5fba07fc5c9ddd927afde85d01a17f5699}
executor.agent.timeout-ms=${EXECUTOR_AGENT_TIMEOUT_MS:90000}
```

These are used automatically when you run `mvn spring-boot:run` locally — no extra setup needed.

---

## Step 3 — Verify the Connection

### From your local machine

```bash
curl -s http://3.110.161.110:8081/v1/healthz
```

Expected response:
```json
{
  "status": "UP",
  "docker": "available",
  "diskFreeGb": 12.5,
  "activeTasks": 0
}
```

### From EC2 itself

```bash
curl http://localhost:8081/actuator/health
```

Expected: HTTP 200 with `{"status":"UP"}`

### Check the executor agent is running

```bash
ssh -i C:\keys\code_execution_engine.pem ubuntu@3.110.161.110
docker ps
```

You should see `codex-executor-agent` with status `Up`.

---

## How the Backend Calls the Execution Engine

File: `Codex_code/src/main/java/com/codex/platform/execution/client/ExecutorAgentClient.java`

The backend sends a `POST /v1/execute` request like this:

```json
{
  "submissionId": "uuid-here",
  "language": "cpp",
  "dockerImage": "codex-cpp:latest",
  "compileCommand": "g++ -O2 -o /workspace/solution /workspace/solution.cpp",
  "executeCommand": "/workspace/solution",
  "fileExtension": ".cpp",
  "sourceCode": "#include<iostream>...",
  "compileTimeoutMs": 10000,
  "runTimeoutMs": 5000,
  "memoryLimitMb": 256,
  "testCases": [
    { "id": "tc1", "stdin": "5\n1 2 3 4 5", "expectedStdout": "15" }
  ]
}
```

The execution engine returns:

```json
{
  "status": "ACCEPTED",
  "compileTimeMs": 312,
  "totalExecTimeMs": 45,
  "passedTestCases": 3,
  "totalTestCases": 3,
  "results": [
    { "id": "tc1", "status": "PASSED", "stdout": "15", "execTimeMs": 12 }
  ]
}
```

---

## What Each Verdict Means

| Verdict | Meaning |
|---|---|
| `ACCEPTED` | All test cases passed |
| `WRONG_ANSWER` | Output didn't match expected |
| `COMPILATION_ERROR` | Code failed to compile |
| `RUNTIME_ERROR` | Program crashed (non-zero exit code) |
| `TIME_LIMIT_EXCEEDED` | Exceeded `runTimeoutMs` (default 5s) |
| `MEMORY_LIMIT_EXCEEDED` | Process killed by OOM (exit code 137) |

---

## Troubleshooting

### Backend can't reach execution engine

```
Connection refused / ConnectException
```

**Check:**
1. Is the container running? → `docker ps` on EC2
2. Is port 8081 open? → AWS Console → Security Group → inbound rule TCP 8081
3. Is the IP correct? → EC2 Console → public IPv4

If container is stopped, restart it:
```bash
cd ~/codex-execution
sudo docker compose -f docker-compose.ec2.yml --env-file .env up -d
```

---

### Backend gets HTTP 401 from execution engine

```
401 Unauthorized
```

Token mismatch. The token in the backend env var doesn't match the token in EC2's `.env`.

**Fix:** Copy the token from EC2 (`cat ~/codex-execution/.env`) and set the same value as `EXECUTOR_AGENT_TOKEN` in Render.

---

### Submissions stuck / not returning results

**Check execution engine logs:**
```bash
docker logs -f codex-executor-agent
```

**Check backend logs on Render** → Live Tail → look for errors from `ExecutorAgentClient`.

---

## Current Connection Details

| Item | Value |
|---|---|
| EC2 Public IP | `3.110.161.110` |
| Execution Engine Port | `8081` |
| Full Base URL | `http://3.110.161.110:8081` |
| Health Check URL | `http://3.110.161.110:8081/v1/healthz` |
| EC2 SSH Key | `C:\keys\code_execution_engine.pem` |
| EC2 User | `ubuntu` |

> **Note:** If the EC2 instance is stopped and restarted, the public IP may change. Update `EXECUTOR_AGENT_BASE_URL` in Render and `application-dev.properties` with the new IP.
