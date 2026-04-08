FROM eclipse-temurin:17-jdk

# Generate Class Data Sharing (CDS) archive.
# Without this: JVM cold-starts in ~2-3s loading core classes from scratch.
# With this:    JVM starts in ~0.5s using the pre-mapped shared archive.
RUN java -Xshare:dump 2>/dev/null || true

# Pre-extract the JDK module graph for faster javac startup
RUN javac --version > /dev/null 2>&1

WORKDIR /workspace
