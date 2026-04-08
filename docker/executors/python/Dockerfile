FROM python:3.11-slim

# Pre-compile all standard library modules to .pyc bytecode.
# Without this: Python compiles each imported module on first use (~200-500ms overhead).
# With this:    Imports load pre-compiled bytecode instantly.
RUN python -m compileall -q /usr/lib/python3.11 2>/dev/null || true

WORKDIR /workspace
