# SQL Execution (SQLite) — Design & Rationale

This document explains how SQL query execution was added to the Codex platform,
why we use **SQLite** instead of MySQL/Postgres, and the exact trick that let us
add it with **zero changes to the execution engine**.

---

## 1. TL;DR

- SQL problems run on **SQLite** (the `sqlite3` CLI), not a database server.
- A "SQL submission" reuses the existing generic executor: image + `executeCommand`
  + per-test-case stdin + text comparison of stdout.
- Each **test case's `input`** holds the **schema + seed SQL** (the dataset).
  The **user's submission** is the **query**. The query's result rows are compared
  as text against the test case's `expectedOutput`.
- Row order is only guaranteed with `ORDER BY`, so **SQL problems must specify the
  ordering** in the statement and store `expectedOutput` in that order.

---

## 2. MySQL vs SQLite — the difference

| | **SQLite** | **MySQL / Postgres** |
|---|---|---|
| Architecture | A **library / CLI**. No background process. The whole DB is one file. `sqlite3` opens the file, runs SQL, prints, exits. | A **server**. A long-running process (`mysqld`) listens on a port; clients connect to run queries. |
| Fits "one throwaway container per submission" | **Yes** — behaves exactly like `python solution.py`. | **No** — you cannot boot a fresh server per submission (slow startup, heavy). |
| Idle memory | **~0 MB** — only uses RAM while a query runs, then frees it. | **~200–400 MB** resident, always, before any query. |
| Isolation between submissions | **Total** — separate file, separate container, deleted after. | **Weaker** — all submissions share one server; you must namespace + clean up schemas so users can't see/corrupt each other's data. |
| Engine code changes needed | **None** | Significant — a whole new JDBC-based code path. |
| SQL dialect | Standard SQL; missing some MySQL-isms (`DATE_FORMAT`, `IF()`, some date math). Modern SQLite (3.25+) has CTEs, window functions, most joins. | Full MySQL dialect (matches LeetCode). |

### Why MySQL could NOT be done (here)

Two hard blockers, both rooted in the deployment target — a **1 GB AWS EC2
t2.micro (free tier)** that already runs the Spring Boot executor agent (a JVM):

1. **Memory.** MySQL/Postgres idles at 200–400 MB *before running a single query*.
   On a 1 GB box shared with the JVM, that pushes the machine into swapping and
   OOM-kills. SQLite adds essentially nothing.

2. **The execution model.** The engine's entire design is *"create a container,
   run one command, capture stdout, destroy the container."* A DB **server**
   doesn't fit that: you'd have to run **one long-lived MySQL instance** on the
   host and give each submission a **temporary, namespaced schema** that you
   create before and drop after — plus all the isolation/cleanup/security work
   that comes with many untrusted users sharing one server. That is a different
   architecture, not a config change.

MySQL only wins on **dialect compatibility** (copying LeetCode's MySQL problems
verbatim). That was not worth trading the free tier and a rewrite for.

### Why SQLite works

SQLite is not a server — it's a command that reads a file and prints a result.
That makes it a drop-in "language" in the existing engine: it slots in next to
C++/Python/Java/JavaScript with the same lifecycle (write file → run in a locked
container → compare stdout → destroy container). It also costs almost no memory,
so it's the *only* DB option that comfortably fits the t2.micro.

---

## 3. How we pulled it off

### 3.1 The generic engine (unchanged)

`ExecutionRunner` / `DockerExecutor` already do this for every language:

1. Write the submission's source to `/workspace/solution<ext>` (bind-mounted).
2. Start **one** locked container (network=none, caps dropped, read-only rootfs,
   seccomp, PID limit).
3. Optionally compile (skipped when `compileCommand` is null).
4. For each test case: write its `stdin` to `/workspace/input.txt`, run
   `executeCommand < /workspace/input.txt`, capture stdout.
5. Compare stdout to `expectedStdout` via `OutputNormalizer` (trims trailing
   whitespace, normalizes newlines).
6. Destroy the container, delete the temp dir.

We reused **all** of this. SQL is just a new row in the `languages` table + a tiny
Docker image.

### 3.2 The field re-mapping

| Generic engine field | For SQL problems it means |
|---|---|
| `sourceCode` (the submission) | The user's SQL **query** |
| test case `input` / `stdin` | The **schema + seed SQL** (the dataset for that case) |
| test case `expectedOutput` | The expected **result rows** (as text) |

So a single problem can have several test cases, each with a **different dataset**,
and the same user query is run against each.

### 3.3 The `executeCommand` (the actual trick)

Registered on the SQL `Language` row (see `DataInitializer.ensureSqlLanguage()`):

```sh
sh -c 'rm -f /tmp/q.db; sqlite3 --safe -bail /tmp/q.db < /workspace/input.txt >/dev/null 2>&1; sqlite3 --safe -bail /tmp/q.db < /workspace/solution.sql'
```

What it does, in order:
1. `rm -f /tmp/q.db` — **reset the database** (see the warning below).
2. `sqlite3 ... < input.txt` — load the schema + seed into a fresh DB at
   `/tmp/q.db` (output suppressed; this is trusted, author-provided setup).
3. `sqlite3 ... < solution.sql` — run the **user's query** against that DB and
   print the result rows to stdout (which the engine compares).

> ### ⚠️ Why `rm -f /tmp/q.db` is mandatory
>
> The executor creates **one container per submission** and runs **every test case
> inside that same container** (see `ExecutionRunner`). Its `/tmp` therefore
> **persists across test cases**. Without the reset:
>
> - **Test 1** creates `/tmp/q.db`, runs `CREATE TABLE` + `INSERT`s → correct.
> - **Test 2** finds `/tmp/q.db` already there → its `CREATE TABLE` fails
>   ("table already exists"), the error is swallowed by `>/dev/null 2>&1`, and the
>   query then runs against **test 1's stale data** → a silently wrong verdict.
>
> Every test case must begin from an empty database. Do not remove the `rm -f`.
> This also means **each test case's input must contain its own full
> `CREATE TABLE` + `INSERT`s** — it can never rely on a previous case's setup.

**Why the `sh -c '...'` wrapper is essential.** The engine appends
` < /workspace/input.txt` to whatever `executeCommand` is. Without the wrapper,
that trailing redirect would attach to the *last* command in the chain and
**override** the inner `< /workspace/solution.sql` — so the user's query would
never be read (last `<` wins in the shell). Wrapping the whole pipeline in
`sh -c '...'` makes the appended redirect attach to the **outer** shell (harmless),
while the inner explicit redirects keep control of what each `sqlite3` reads.

Verified with a `cat` stand-in:

```
final command:  sh -c '... cat < input.txt ...; ... cat < solution.sql' < input.txt
result:         setup reads input.txt, query reads solution.sql   ✓  (no hijack)
```

**Flags:**
- `--safe` — disables SQLite's dangerous CLI features (`.shell`, `.system`,
  `ATTACH`, `.load`) as defense-in-depth. (The container sandbox — no network,
  no caps, read-only rootfs — is the real protection; `--safe` is a cheap extra
  layer and can be dropped if it ever causes trouble.)
- `-bail` — stop on the first SQL error so a broken query exits non-zero, which
  the engine reports as `RUNTIME_ERROR`.

**Output format:** default SQLite "list" mode — one row per line, columns joined
by `|`, **no header**. We deliberately omit `.header on`: headers would force the
`expectedOutput` to match column names, so `SELECT name` vs `SELECT name AS n`
would wrongly diverge. Comparing data-only rows is more forgiving.

### 3.4 The row-order convention

SQLite (like any SQL engine) does **not** guarantee row order without `ORDER BY`.
Because the platform compares output **line-by-line as text**, the same correct
query could pass or fail run-to-run if order is unspecified.

**Our rule:** every SQL problem states the required ordering and includes
`ORDER BY` in the intended solution; `expectedOutput` is stored in that order.
(The alternative — sorting both sides before comparing — was not needed and would
add complexity to `OutputNormalizer`.)

---

## 3.5 Authoring SQL problems (admin + playlist, AI + manual)

Problems can be created from two surfaces — the **admin page** and a **user's
playlist** — and each offers **manual** and **AI** authoring. Both surfaces reuse
the *same* components (`ProblemForm` and `AiProblemGenerator` → `AiProblemService`),
so SQL support was added in one place and covers all four combinations.

**Manual** (`ProblemForm`) needed no changes — it is already generic. To author a
SQL problem by hand:
- **Test case Input** → the full `CREATE TABLE` + `INSERT`s for that case.
- **Test case Expected Output** → the query's rows, one per line, `|`-separated, no header.
- **Description** → show the schema and state the required ordering.

**AI** (`AiProblemService.SYSTEM_INSTRUCTION`) *did* need changing. The prompt
previously described only a stdin/stdout program judge and allowed
`referenceSolution.language` of Python/Java/C++/JavaScript, so it could never emit a
valid SQL problem. It now has two modes:
- **PROGRAM MODE** (default, unchanged) — stdin/stdout reference program.
- **SQL MODE** — triggered when the requested problem is a database/query problem.
  The model emits `referenceSolution.language: "SQL"` with the query as `sourceCode`,
  puts the schema + seed in each `testCases[].input`, and writes `expectedOutput` as
  `|`-separated rows. It is instructed to always `ORDER BY`, to use SQLite dialect,
  and to repeat the full schema in every test case (because the DB is reset per case).

**Verification comes free.** `SolutionVerifier` runs the AI's reference solution
through the real execution engine to replace guessed outputs with ground truth. It is
language-agnostic — it just looks up the `Language` row — so once SQL was registered,
SQL drafts verify through the exact same path as a real submission. The only fix
needed was adding SQL aliases (`sqlite`, `mysql`, …) to `resolveLanguage()`: an
unrecognized name silently **falls back to Python**, which would have run a SQL query
as a Python program.

---

## 4. Files changed

| File | Change |
|---|---|
| `docker/executors/sql/Dockerfile` | New. `FROM alpine:3.20` + `apk add sqlite`. (Mirrored in `Codex_code/docker/executors/sql/Dockerfile`.) |
| `docker/executors/build-all.sh` | Added `build_one sql`. (Backend copy: added `[sql]=codex-sql:latest` to the image map.) |
| `Codex_code/.../config/DataInitializer.java` | `ensureSqlLanguage()` registers/repairs the SQL language (upsert); `ensureSqlSampleProblem()` seeds one testable problem. |
| `Codex_code/.../ai/AiProblemService.java` | Added SQL MODE to `SYSTEM_INSTRUCTION` so AI authoring can emit valid SQL problems. |
| `Codex_code/.../ai/SolutionVerifier.java` | Added SQL aliases to `resolveLanguage()` so a SQL draft isn't silently verified as Python. |
| `codex-front/.../problem-solve/SolvePanel.jsx` | SQL syntax highlighting in Monaco; skip the language-mismatch heuristic for SQL. |

No changes were needed in `DockerExecutor`, `ExecutionRunner`, `ExecutionService`,
`ProblemForm`, `PlaylistService`, or any DTO.

---

## 5. Build & deploy on EC2

```bash
# 1. Build the SQLite executor image (on the EC2 host, next to the others)
./docker/executors/build-all.sh sql        # or build-all.sh to rebuild all

# 2. Confirm the image exists
docker images | grep codex-sql

# 3. Restart the backend so DataInitializer registers the SQL language + sample
#    problem (idempotent — safe to run against the existing prod DB).
```

The SQL `Language` row (`codex-sql:latest`, `.sql`, the executeCommand above) is
created automatically on startup if missing.

### Quick manual sanity check (on any host with Docker)

```bash
mkdir -p /tmp/sqltest && cd /tmp/sqltest
printf "CREATE TABLE t(name TEXT); INSERT INTO t VALUES('Alice'),('Bob');" > input.txt
printf "SELECT name FROM t ORDER BY name;" > solution.sql
docker run --rm --network none -v /tmp/sqltest:/workspace codex-sql:latest \
  sh -c 'sqlite3 --safe -bail /tmp/q.db < /workspace/input.txt >/dev/null 2>&1; sqlite3 --safe -bail /tmp/q.db < /workspace/solution.sql'
# expected:
# Alice
# Bob
```

---

## 6. Things to validate on first EC2 run

- **Seccomp.** The shared `seccomp-judge.json` must allow SQLite's syscalls
  (open/read/write/mmap/fsync). Python and g++ need the same set, so this is
  expected to pass — confirm with one real submission.
- **`--safe` availability.** Requires SQLite ≥ 3.34; Alpine 3.20 ships ≥ 3.45. If
  a pinned older base is ever used, drop `--safe` (the sandbox still contains it).
- **tmpfs size.** The DB is written to the container's `/tmp` (64 MB tmpfs). Fine
  for problem-sized datasets; bump the mount if you add large seed data.

---

## 7. Known limitations / future work

- **Dialect:** SQLite, not MySQL. `DATE_FORMAT`, `IF()`, and some date functions
  differ. Author problems in SQLite syntax.
- **No dedicated SQL verdict:** a SQL syntax error surfaces as `RUNTIME_ERROR`
  (there is no compile phase for interpreted languages). Good enough; a nicer
  "SQL_ERROR" label would require an engine change.
- **Order-independent problems:** not supported yet. Would need "sort both sides
  before compare" in `OutputNormalizer`. Current rule: always require `ORDER BY`.
