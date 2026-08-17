# Build stage — Maven + JDK 25; also runs the frontend build (frontend-maven-plugin
# downloads its own node/npm), producing a single self-contained jar.
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY frontend ./frontend
RUN mvn -B -DskipTests package

# Runtime — slim JRE 25
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/backlog-pulse-*.jar app.jar
EXPOSE 8080

# Memory-lean JVM defaults for a mostly-idle, single-instance dashboard. Without these the
# JVM sizes its heap off the host (25% of an 8 GB box = 2 GB max heap) and G1 keeps whatever
# it grabs, so resident memory — what Railway bills by the GB-minute — drifts to 2–3 GB.
#   -Xmx640m / SerialGC + low HeapFreeRatio  → small heap that shrinks back after a burst
#   TieredStopAtLevel=1 + small code cache   → C1 only: less compiler/code-cache memory
#   MaxMetaspace / Xss / MaxDirectMemory     → cap the other native pools
#   ExitOnOutOfMemoryError                   → die (Railway restarts) instead of thrashing
# The ONNX embedding model (~500 MB native) is the remaining fixed cost.
# Override per deployment with the JAVA_OPTS env var (replaces the whole list).
ENV JAVA_OPTS="-Xms96m -Xmx640m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 \
  -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:MaxMetaspaceSize=160m \
  -Xss512k -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
