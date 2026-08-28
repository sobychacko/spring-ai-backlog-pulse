# Build stage — Maven + JDK 25; also runs the frontend build (frontend-maven-plugin
# downloads its own node/npm), producing a single self-contained jar.
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY frontend ./frontend
RUN mvn -B -DskipTests package

# Warm DJL's native cache: the embedding path needs DJL's PyTorch engine (NDArray backend for
# the tokenizer output), and on first use DJL downloads ~150 MB of libtorch natives — per cold
# boot on an ephemeral filesystem. Triggering engine init here (DJL's own download logic, so
# the cache layout is guaranteed right) bakes them into the image instead.
RUN printf 'public class WarmDjl { public static void main(String[] a) { ai.djl.engine.Engine.getEngine("PyTorch"); } }' > WarmDjl.java \
 && mvn -B -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt \
 && javac -cp "$(cat /tmp/cp.txt)" WarmDjl.java \
 && DJL_CACHE_DIR=/opt/djl-cache java -cp ".:$(cat /tmp/cp.txt)" WarmDjl \
 && ls /opt/djl-cache/pytorch

# Embedding model — fetched at build time so a cold boot never downloads it. Matters for
# serverless deployments (Railway sleeps the service after 10 idle minutes; the ephemeral
# filesystem cache would be lost), and it also makes every deploy's first boot faster.
# Keep in sync with spring.ai.embedding.transformer.* in application.yml.
FROM curlimages/curl:latest AS model
ARG HF=https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/main
RUN curl -fsSL -o /tmp/model.onnx     $HF/onnx/model.onnx \
 && curl -fsSL -o /tmp/tokenizer.json $HF/tokenizer.json

# Runtime — slim JRE 25
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/backlog-pulse-*.jar app.jar
COPY --from=model /tmp/model.onnx /tmp/tokenizer.json /app/models/
COPY --from=build /opt/djl-cache /opt/djl-cache
# file: URIs bypass Spring AI's resource cache and are used in place; DJL_CACHE_DIR points at
# the pre-warmed PyTorch natives so a cold boot downloads nothing
ENV SPRING_AI_EMBEDDING_TRANSFORMER_ONNX_MODEL_URI=file:/app/models/model.onnx \
    SPRING_AI_EMBEDDING_TRANSFORMER_TOKENIZER_URI=file:/app/models/tokenizer.json \
    DJL_CACHE_DIR=/opt/djl-cache
EXPOSE 8080

# Memory-lean JVM defaults for a mostly-idle, single-instance dashboard. Without these the
# JVM sizes its heap off the host (25% of an 8 GB box = 2 GB max heap) and G1 keeps whatever
# it grabs, so resident memory — what Railway bills by the GB-minute — drifts to 2–3 GB.
#   -Xmx1g -Xmn64m / SerialGC + HeapFreeRatio → old gen sized for the transient ~420 MB byte[]
#     Spring AI allocates when the ONNX model loads (lazily, on first embedding use — see
#      LazyEmbeddingConfig); StartupHeapTrim GCs post-boot, later spikes shrink back via GC
#   TieredStopAtLevel=1 + small code cache   → C1 only: less compiler/code-cache memory
#   MaxMetaspace / Xss / MaxDirectMemory     → cap the other native pools
#   ExitOnOutOfMemoryError                   → die (Railway restarts) instead of thrashing
# The ONNX embedding model (~500 MB native) is the remaining fixed cost.
# Override per deployment with the JAVA_OPTS env var (replaces the whole list).
ENV JAVA_OPTS="-Xms96m -Xmx1g -Xmn64m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=25 \
  -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=48m -XX:MaxMetaspaceSize=160m \
  -Xss512k -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
