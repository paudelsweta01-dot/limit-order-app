# syntax=docker/dockerfile:1.7
#
# Multi-stage build for the limit-order backend.
#   - Stage 1 ("build"): Maven 3.9 / JDK 17 image; compiles the fat JAR.
#   - Stage 2 ("runtime"): JRE 17 alpine; ships only the JAR + curl for
#     the actuator healthcheck.
#
# Both base images are multi-arch (linux/amd64 + linux/arm64) so the
# image builds natively on Apple Silicon and CI runners alike. No
# platform pin needed in compose.

# ---------- build stage ----------

FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Copy the POM first so the dependency resolution layer is cached when
# only `src/` changes — saves ~30s of re-download per rebuild.
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# ---------- runtime stage ----------

FROM eclipse-temurin:17-jre-alpine AS runtime

# `curl` for the HEALTHCHECK probe. ~5 MB; cheaper than depending on
# busybox wget's spider mode (which would need a HEAD response that
# /actuator/health doesn't always provide).
RUN apk add --no-cache curl

# Default JVM tuning — overridable at run time by setting
# JAVA_TOOL_OPTIONS in compose / `docker run -e ...`.
#   MaxRAMPercentage=75 → respect cgroup memory limits set by compose.
#   +ExitOnOutOfMemoryError → fail fast under sustained pressure so
#     compose's restart policy reschedules the container instead of
#     leaving a half-dead JVM behind.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

WORKDIR /app

# Spring Boot's Maven plugin renames the pre-repackaged jar to
# `*.jar.original`, so the `lob-backend-*.jar` glob picks up only the
# repackaged fat jar.
COPY --from=build /build/target/lob-backend-*.jar /app/app.jar

EXPOSE 8080

# Run as a fixed numeric uid:gid. Linux accepts numeric ids without an
# /etc/passwd entry, so we don't need to `addgroup` / `adduser` here —
# logging frameworks that look up the username will just log `?`,
# which is harmless and explicit.
USER 1000:1000

HEALTHCHECK --interval=15s --start-period=30s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
