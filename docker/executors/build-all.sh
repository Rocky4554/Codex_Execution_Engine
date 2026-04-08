#!/usr/bin/env bash
# Build all language executor images.
# Run this on EC2 (or any Docker host) after cloning the repo.
# Usage: ./docker/executors/build-all.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Building codex-cpp..."
docker build -t codex-cpp:latest "$SCRIPT_DIR/cpp"

echo "Building codex-java..."
docker build -t codex-java:latest "$SCRIPT_DIR/java"

echo "Building codex-python..."
docker build -t codex-python:latest "$SCRIPT_DIR/python"

echo "Building codex-javascript..."
docker build -t codex-javascript:latest "$SCRIPT_DIR/javascript"

echo ""
echo "All executor images built:"
docker images | grep "codex-"
