FROM gcc:latest

# Precompile bits/stdc++.h — the #1 cause of compilation timeouts.
# Without this: g++ spends 30-60s parsing the entire C++ stdlib on every submission.
# With this:    g++ finds the .gch file and compiles in ~1-2s.
RUN g++ -std=c++11 -O2 -x c++-header \
    $(find /usr/include -name stdc++.h 2>/dev/null | head -1) \
    -o /usr/include/x86_64-linux-gnu/c++/$(g++ -dumpversion)/bits/stdc++.h.gch 2>/dev/null || \
    g++ -std=c++11 -O2 -x c++-header \
    /usr/local/include/c++/$(g++ -dumpversion)/x86_64-pc-linux-gnu/bits/stdc++.h 2>/dev/null || \
    echo "PCH: falling back to find" && \
    find / -path "*/bits/stdc++.h" -exec g++ -std=c++11 -O2 -x c++-header {} \; 2>/dev/null

WORKDIR /workspace
