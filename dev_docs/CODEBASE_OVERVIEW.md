# Codex Execution Engine — Codebase Overview

## What It Is

A **distributed, sandboxed code execution engine** — a Spring Boot REST service deployed on an AWS EC2 instance. Its job is to safely receive code submissions from a backend (hosted on Render), execute them inside isolated Docker containers, and return detailed verdicts.

**Supported Languages:** C++, Java, Python, JavaScript

**Possible Verdicts:** `ACCEPTED`, `WRONG_ANSWER`, `COMPILATION_ERROR`, `RUNTIME_ERROR`, `TIME_LIMIT_EXCEEDED`, `MEMORY_LIMIT_EXCEEDED`

---

## High-Level Architecture

```
Backend (Render)
    │  POST /v1/execute
    ▼
EC2 Agent (this project)
    │  Creates Docker container per submission
    ▼
Isolated Docker container
    │  Compile → Run each test case
    ▼
Verdict returned to backend
```

The separation exists because this EC2 agent owns the host filesystem and can safely bind-mount volumes into Docker containers — something impossible from a managed platform like Render.

---

## Directory & File Structure

```
Codex_Execution_Engine/
├── src/main/java/com/codex/agent/
│   ├── AgentApplication.java                    # Spring Boot entry point
│   ├── config/
│   │   └── DockerConfig.java                   # Docker client bean configuration
│   ├── controller/
│   │   └── ExecuteController.java              # REST API endpoints
│   ├── dto/
│   │   ├── ExecuteRequest.java                 # Request payload model
│   │   └── ExecuteResponse.java                # Response payload model
│   ├── execution/
│   │   ├── DockerExecutor.java                 # Core Docker orchestration logic
│   │   ├── ExecutionResult.java                # Internal execution result DTO
│   │   └── OutputNormalizer.java               # Test output comparison utility
│   ├── security/
│   │   └── BearerTokenFilter.java              # Bearer token authentication filter
│   ├── scheduled/
│   │   └── TempDirJanitor.java                 # Cleanup scheduled task
│   └── service/
│       └── ExecutionRunner.java                # High-level orchestration service
├── src/main/resources/
│   ├── application.yml                         # Spring Boot configuration
│   └── seccomp-judge.json                      # Seccomp security policy (syscall filtering)
├── dev_docs/                                   # Developer documentation
├── docker/
│   └── executors/                              # Per-language sandbox Docker images
├── pom.xml                                     # Maven build configuration
├── Dockerfile                                  # Multi-stage Docker image build
├── docker-compose.ec2.yml                      # EC2 deployment configuration
├── .env.example                                # Environment variable template
├── MEMORY_OPTIMIZATION.md                      # JVM heap tuning guide for small EC2
├── RECOVERY.md                                 # Disaster recovery & rebuild procedures
├── setup-host.sh                               # One-shot EC2 host bootstrap
├── codex-cleanup.sh                            # Heavy hourly cleanup script
├── codex-cleanup-light.sh                      # Lightweight 5-min cleanup script
└── test-all-languages.sh                       # Smoke test script
```

---

## Component Reference

### `AgentApplication.java`
- Spring Boot entry point
- Enables scheduling (`@EnableScheduling`) for `TempDirJanitor`
- Forces UTC timezone globally for consistent timestamps

### `DockerConfig.java`
- Creates the Docker client bean using docker-java-core + zerodep transport
- Configurable Docker host (default: `unix:///var/run/docker.sock`)
- Connection timeout: 30s, Response timeout: 120s, Max connections: 100

### `ExecuteController.java`
Three endpoints:
- `POST /v1/execute` — Main submission execution endpoint
- `GET /v1/healthz` — Liveness probe (checks Docker daemon, disk free, concurrency state)
- `GET /v1/version` — Version information

Key behaviors:
- **Concurrency limiting:** Semaphore-based cap on in-flight executions (tunable via `executor.agent.max-concurrent`, default 1)
- **Idempotency caching:** 5-minute TTL cache (256 max entries, LRU eviction) — replays responses for duplicate `submissionId` POSTs
- **Bearer token auth:** Delegated to `BearerTokenFilter`

### `BearerTokenFilter.java`
- Protects `POST /v1/execute` with shared secret (`EXECUTOR_AGENT_TOKEN`)
- Public endpoints exempt: `/v1/healthz`, `/v1/version`, `/actuator/health`
- **Constant-time string comparison** to prevent timing side-channel leaks
- Fails closed — if token not configured, all authenticated requests return HTTP 503

### `ExecuteRequest.java`
Validated request payload fields:
| Field | Description |
|---|---|
| `submissionId` | UUID for idempotency |
| `language` | e.g. `cpp`, `java`, `python` |
| `dockerImage` | Sandbox image to use |
| `compileCommand` | Optional compile command |
| `executeCommand` | Run command |
| `fileExtension` | e.g. `.cpp`, `.java` |
| `sourceCode` | Max 256 KB |
| `compileTimeoutMs` | Compile timeout |
| `runTimeoutMs` | Per-test-case run timeout |
| `memoryLimitMb` | Memory cap |
| `testCases[]` | Array of `{id, stdin, expectedStdout}` |

### `ExecuteResponse.java`
Response payload:
| Field | Description |
|---|---|
| `status` | Final verdict |
| `compileOutput` | Compiler stdout/stderr |
| `compileTimeMs` | Compile duration |
| `totalExecTimeMs` | Total wall-clock time |
| `passedTestCases` / `totalTestCases` | Test case counts |
| `stdout`, `stderr` | Last test case output |
| `results[]` | Per-test result: `{status, stdout, stderr, exitCode, execTimeMs}` |

---

## Execution Engine Deep Dive

### `DockerExecutor.java` — Core Logic

Orchestrates the full Docker-based execution pipeline:

#### 1. `prepareTempDirectory()`
Creates a host temp dir and writes source code file:
```
/tmp/codex/submissions/exec-XXXX/solution.ext
```

#### 2. `ensureImageExists()`
Inspects image locally; pulls from registry if missing (5-minute timeout).

#### 3. `createAndStartContainer()`
Creates container with:
- **Bind mount:** `workDir ↔ /workspace` inside container
- **Network isolation:** `network_mode: none` (no internet/LAN access)
- **Resource limits:**
  - Memory: `memoryLimitMb × 1024 × 1024` bytes (swap = same)
  - CPU quota: 50% of one core (`cpuQuota=50000`)
  - PIDs limit: 50 (prevents fork bombs)
- **Filesystem restrictions:**
  - Read-only root filesystem
  - `/tmp` tmpfs (64 MB, `noexec`, `nosuid`)
- **Capability drop:** Drop ALL Linux capabilities
- **Security options:** `no-new-privileges` + seccomp filter
- Runs `sleep 120` (keeps container alive for `docker exec` commands)
- 60-second timeout wrapping the create call (guards against hung Docker daemon)

#### 4. `compileInContainer()`
Runs compile command via `docker exec` if provided.
Returns `null` on success, or an `ExecutionResult` describing the failure.

#### 5. `runTestCase()`
Runs program for a single test case:
- Writes stdin to `/workspace/input.txt`
- Executes: `command < /workspace/input.txt`
- Returns `ExecutionResult` with stdout, stderr, exitCode, executionTimeMs

#### 6. `executeCommandInContainer()`
Low-level Docker exec:
- Creates exec instance, captures stdout/stderr to `ByteArrayOutputStream`
- Waits for completion with timeout
- **Retries up to 5 times** (500ms delays) to fetch exit code (handles Docker daemon delay)
- Timeout → exitCode `-1` with error message

#### 7. `cleanup()`
Force removes container + recursively deletes temp directory.

### `seccomp-judge.json`
Kernel syscall filter (default: ALLOW, with specific blocks):
| Blocked Syscall | Reason |
|---|---|
| `ptrace` | Prevents process inspection/modification |
| `perf_event_open` | Blocks hardware counter attacks |
| `io_uring_*` | Large kernel attack surface |
| `unshare` | Prevents namespace escape |
| `*_module` | Blocks kernel module loading |

---

## Execution Service

### `ExecutionRunner.java` — High-Level Orchestration

Drives the full submission workflow:
1. Prepare temp directory
2. Create container
3. Compile (or skip for interpreted languages)
   - Compile fail → `COMPILATION_ERROR`, all tests marked `SKIPPED`
4. Run each test case (serially, same container):
   - Catches exceptions → `RUNTIME_ERROR`
   - Compares output via `OutputNormalizer.areEqual()`
   - Exit code `137` → `MEMORY_LIMIT_EXCEEDED`
   - Exit code `-1` + "timed out" → `TIME_LIMIT_EXCEEDED`
   - Belt-and-braces: also checks wall-clock time > `runTimeoutMs`
   - First failure wins; remaining tests marked `SKIPPED`
5. `cleanup()` always runs in `finally` block
6. Returns `ExecuteResponse` with verdict, per-test results, timing
7. Logs ASCII summary box for easy log scanning

### `OutputNormalizer.java`
Normalizes output before comparison:
- Converts `\r\n` → `\n`
- Strips trailing whitespace from each line
- Trims leading/trailing blank lines

---

## Data Flow

```
Backend (Render)
    │
    │  POST /v1/execute  {ExecuteRequest}
    ▼
[BearerTokenFilter]  ──── invalid token ──→  HTTP 401
    │
[ExecuteController]
    ├── Idempotency cache hit ──→  return cached response immediately
    ├── Semaphore acquire (timeout 60s)
    │   └── no permit available ──→  HTTP 503
    ▼
[ExecutionRunner]
    │
    ├── prepareTempDirectory()
    │   └── write source code to /tmp/codex/submissions/exec-XXXX/solution.ext
    │
    ├── createAndStartContainer()
    │   ├── ensureImageExists() (pull if missing)
    │   └── create container: bind mount + limits + caps drop + seccomp
    │
    ├── compileInContainer()  [if compileCommand provided]
    │   └── FAIL → COMPILATION_ERROR, all tests → SKIPPED
    │
    ├── for each TestCase (serially):
    │   ├── write input.txt to bind-mounted workspace
    │   ├── executeCommandInContainer() with input redirect
    │   └── verdict logic:
    │       ├── exit 137      → MEMORY_LIMIT_EXCEEDED
    │       ├── exit -1       → TIME_LIMIT_EXCEEDED
    │       ├── exit != 0     → RUNTIME_ERROR
    │       ├── output match  → PASSED
    │       └── output mismatch → WRONG_ANSWER
    │
    └── finally: cleanup() → remove container + delete temp dir
    │
[ExecuteController]
    ├── cache result (5-min TTL)
    ├── release semaphore
    └── return ExecuteResponse
    │
    ▼
Backend (Render)
    └── persist submission status + results
```

---

## Security Architecture

| Layer | Mechanism |
|---|---|
| Transport | Bearer token (shared secret, constant-time compare) |
| Network | `network_mode: none` — no outbound internet from container |
| Filesystem | Read-only root + tmpfs `/tmp` (64 MB, noexec) |
| Linux capabilities | Drop ALL |
| Privilege escalation | `no-new-privileges` |
| Syscalls | Seccomp filter blocks ptrace, io_uring, unshare, kernel modules |
| Resources | Memory cap, CPU quota 50%, PIDs limit 50 |

---

## Exit Code Interpretation

| Exit Code | Verdict |
|---|---|
| `0` | Success |
| `137` | `MEMORY_LIMIT_EXCEEDED` (kernel OOM SIGKILL) |
| `-1` | `TIME_LIMIT_EXCEEDED` (docker exec deadline exceeded) |
| Other non-zero | `RUNTIME_ERROR` |

---

## Configuration

All tunables in `application.yml`, overridable via environment variables:

| Config Key | Env Var | Default | Description |
|---|---|---|---|
| `server.port` | — | `8081` | HTTP port |
| `execution.docker.host` | `EXECUTION_DOCKER_HOST` | `unix:///var/run/docker.sock` | Docker socket |
| `execution.temp-dir` | `EXECUTION_TEMP_DIR` | `/tmp/codex/submissions` | Temp directory for submissions |
| `execution.default-time-limit-ms` | — | `5000` | Default per-test timeout |
| `execution.default-memory-limit-mb` | — | `256` | Default memory cap |
| `executor.agent.token` | `EXECUTOR_AGENT_TOKEN` | *(required)* | Shared secret |
| `executor.agent.max-concurrent` | `EXECUTOR_AGENT_MAX_CONCURRENT` | `1` | Max parallel submissions |
| `executor.agent.version` | `EXECUTOR_AGENT_VERSION` | `dev` | Version string |
| `executor.agent.janitor.max-age-minutes` | — | `60` | Orphan dir age before cleanup |

---

## Deployment

### Dockerfile (multi-stage)
- **Stage 1:** Maven build on `eclipse-temurin:17` — caches deps, builds fat JAR
- **Stage 2:** Runtime on `eclipse-temurin:17-jre-jammy`
  - JVM heap: `-Xms64m -Xmx192m` (tuned for tight EC2)
  - Exports port `8081`, timezone UTC

### `docker-compose.ec2.yml`
- Mounts Docker socket (`/var/run/docker.sock`) — agent controls host Docker
- Mounts temp volume (`/tmp/codex`)
- Healthcheck: `curl /actuator/health` every 30s
- Restart policy: `always`

### `setup-host.sh` (one-shot EC2 bootstrap)
1. Installs Docker
2. Builds language sandbox images (labels them `codex.keep=true`)
3. Starts executor agent via docker-compose
4. Installs cleanup scripts + registers cron jobs
5. Disables runaway `apt` timers (prevents disk lock deadlocks)

---

## Cleanup Strategy

Multi-layered approach to prevent disk/memory bloat on tight EC2:

| Layer | Trigger | Scope |
|---|---|---|
| `finally` block | After every execution | Remove container + temp dir |
| `TempDirJanitor` | Every 30 min | Orphaned `exec-*` dirs older than 60 min |
| `codex-cleanup-light.sh` | Every 5 min (cron) | Stopped containers + dangling image layers only |
| `codex-cleanup.sh` | Every 1 hour (cron) | Non-codex images, truncated logs >50 MB, apt cache, journal |

**Image protection:** Images labelled `codex.keep=true` or named `codex-*` are exempt from pruning.

---

## Key Design Decisions

| Decision | Rationale |
|---|---|
| One container per submission (not per test case) | Faster — avoids container startup cost for each test |
| Container runs `sleep 120` | Stays alive for `docker exec` calls across all test cases |
| Serial test case execution | Simpler, deterministic, predictable resource usage |
| Fail-fast on first test failure | Remaining tests marked SKIPPED — avoids wasting resources |
| Semaphore default = 1 | Prevents resource contention on tight EC2 t3.small/t3.micro |
| Idempotency cache | Prevents double-execution if backend retries on network error |
| JVM heap `-Xmx192m` | Fits within ~400 MB total EC2 footprint alongside Docker daemon |
| Input via bind-mount + shell redirect | Avoids stdin piping complexity with docker exec |

---

## Summary Table

| Aspect | Detail |
|---|---|
| **Purpose** | Sandboxed code executor for competitive programming platform |
| **Deployment** | AWS EC2 (t3.small, ~2 GB RAM), Spring Boot + Docker daemon |
| **API** | Single endpoint: `POST /v1/execute` — stateless, idempotent |
| **Auth** | Bearer token (shared secret) |
| **Concurrency** | Semaphore-gated (default 1 submission at a time) |
| **Execution model** | One container per submission, serial test cases |
| **Typical turnaround** | ~5–10 seconds end-to-end |
| **Cleanup** | Immediate `finally` + janitor task + cron scripts |
| **Horizontal scaling** | Stateless — just run multiple EC2 instances |
