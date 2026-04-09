FROM debian:bookworm-slim

# Install only g++ and the C++ standard library headers — no full gcc toolchain
RUN apt-get update && apt-get install -y --no-install-recommends \
    g++ \
    && rm -rf /var/lib/apt/lists/*

# Precompile bits/stdc++.h to avoid 30-60s parse time on every submission.
RUN PCH_PATH=$(find /usr/include -name "stdc++.h" 2>/dev/null | head -1) && \
    if [ -n "$PCH_PATH" ]; then \
        g++ -std=c++17 -x c++-header "$PCH_PATH" -o "${PCH_PATH}.gch" && \
        echo "PCH built at ${PCH_PATH}.gch"; \
    else \
        echo "stdc++.h not found, skipping PCH"; \
    fi

WORKDIR /workspace
