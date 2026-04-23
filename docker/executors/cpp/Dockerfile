FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    g++ \
    && rm -rf /var/lib/apt/lists/*

# Pre-compile <bits/stdc++.h> so submissions that #include it don't pay the parse cost.
RUN PCH_PATH=$(find /usr/include -name "stdc++.h" 2>/dev/null | head -1) && \
    if [ -n "$PCH_PATH" ]; then \
        g++ -std=c++17 -x c++-header "$PCH_PATH" -o "${PCH_PATH}.gch" && \
        echo "PCH built at ${PCH_PATH}.gch"; \
    else \
        echo "stdc++.h not found, skipping PCH"; \
    fi

WORKDIR /workspace

# Protect this image from being removed by codex-cleanup.sh's hourly prune.
LABEL codex.keep=true
