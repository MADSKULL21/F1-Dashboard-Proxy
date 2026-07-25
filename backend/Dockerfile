# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
# Dependencies are resolved in their own layer so that changing source code
# does not re-download the world on every build.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests run in CI against Testcontainers; repeating them here would need a
# Docker daemon inside the build.
RUN ./mvnw -B -q clean package -DskipTests

# ---------------------------------------------------------------------------
# CDS training run
# ---------------------------------------------------------------------------
# Starting the app once at build time and archiving the classes it loaded cuts
# JVM startup noticeably, which is the cheapest available mitigation for the
# Render free-tier cold starts called out in PRD 13.
#
# spring.context.exit=onRefresh stops the app the moment the context is ready,
# so no port is bound and nothing is served.
#
# NOTE for F2: once Flyway, JPA and Redis are added, this training run will try
# to reach a database and a cache that do not exist during the image build.
# Disable them here (spring.flyway.enabled=false, and a lazy datasource) rather
# than dropping the archive.
FROM eclipse-temurin:25-jre-alpine AS cds
WORKDIR /app
COPY --from=build /build/target/f1-dashboard-api-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted \
 && cd extracted \
 && java -XX:ArchiveClassesAtExit=application.jsa \
         -Dspring.context.exit=onRefresh \
         -jar app.jar

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=cds --chown=spring:spring /app/extracted/ ./
USER spring

EXPOSE 8080

# Render free tier ceiling is 512MB for the whole container, not just the heap.
#   MaxRAMPercentage=70 leaves headroom for metaspace, thread stacks, GC
#     structures and the JVM itself.
#   SerialGC beats G1 in a single-CPU, small-heap container: no concurrent GC
#     threads to pay for.
#   AlwaysActAsServerClassMachine stops the JVM picking client-class defaults
#     when it sees a small container.
#   -XX:SharedArchiveFile consumes the archive built above.
ENV JAVA_OPTS="\
-XX:MaxRAMPercentage=70 \
-XX:+UseSerialGC \
-XX:+AlwaysActAsServerClassMachine \
-XX:SharedArchiveFile=application.jsa \
-XX:-UsePerfData \
-Xss512k"

# Shell form so JAVA_OPTS expands; exec so the JVM is PID 1 and receives SIGTERM
# directly, which graceful shutdown depends on.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
