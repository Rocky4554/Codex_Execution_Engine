FROM node:20-slim

# Pre-cache V8 compiled bytecode for built-in modules.
# Without this: Node.js compiles built-in modules on first require (~100-200ms).
# With this:    Cached bytecode loads instantly.
RUN node -e " \
  require('fs'); require('path'); require('readline'); \
  require('util'); require('stream'); require('buffer'); \
  require('events'); require('string_decoder'); \
  console.log('V8 module cache warmed'); \
" 2>/dev/null || true

WORKDIR /workspace
