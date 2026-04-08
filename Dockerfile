# ── Stage 1: Build ────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Cache dependencies first (only re-downloads when pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build the fat JAR
COPY src ./src
RUN mvn package -DskipTests -B

# ── Stage 2: Run ─────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy the built JAR
COPY --from=builder /build/target/executor-agent.jar app.jar

# Create temp dir for code execution workspace
RUN mkdir -p /tmp/codex/submissions

EXPOSE 8081

# Smaller heap than the main backend — the agent has no JPA/JDBC pool/Redis/SSE.
# 64MB initial, 192MB max should be plenty for the controller + docker-java client.
ENTRYPOINT ["java", \
  "-Xms64m", "-Xmx192m", \
  "-Duser.timezone=UTC", \
  "-jar", "app.jar"]
