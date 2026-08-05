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
ENTRYPOINT ["java", "-jar", "app.jar"]
