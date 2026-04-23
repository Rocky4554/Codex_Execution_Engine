#!/usr/bin/env bash
# Build all language executor images.
# Run this on EC2 (or any Docker host) after cloning the repo.
# Usage: ./docker/executors/build-all.sh
#
# Each image is tagged with `codex.keep=true` so the cleanup cron
# (codex-cleanup.sh) never removes it, even if it ages out of use.
# The Dockerfiles also set this label, so the --label flag here is
# defensive in case someone edits them.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

build_one() {
    local lang="$1"
    echo ">> Building codex-${lang}..."
    docker build \
        --label "codex.keep=true" \
        -t "codex-${lang}:latest" \
        "$SCRIPT_DIR/${lang}"
    echo ">> codex-${lang} OK"
    echo
}

build_one cpp
build_one java
build_one python
build_one javascript

echo "All executor images built:"
docker images | grep -E '^(codex-|REPOSITORY)'
