FROM alpine:3.20

# SQLite CLI only — there is NO server process. `sqlite3` opens a file, runs the
# SQL, prints the result, and exits, exactly like an interpreter. Resident memory
# is ~0 when idle, which is why this is the only DB engine that fits a 1GB t2.micro
# alongside the JVM agent. See dev_docs/SQL_EXECUTION.md for the full rationale.
RUN apk add --no-cache sqlite

WORKDIR /workspace

# Protect this image from being removed by codex-cleanup.sh's hourly prune.
LABEL codex.keep=true
