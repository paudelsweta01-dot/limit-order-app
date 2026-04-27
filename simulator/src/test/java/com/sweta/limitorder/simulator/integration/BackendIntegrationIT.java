package com.sweta.limitorder.simulator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.sweta.limitorder.simulator.api.LobApiClient;
import com.sweta.limitorder.simulator.api.TokenCache;
import com.sweta.limitorder.simulator.mode.ScenarioRunner;
import com.sweta.limitorder.simulator.mode.SeedCredentials;
import com.sweta.limitorder.simulator.report.RunReport;

/**
 * Plan §8.3 — full integration test.
 *
 * <p>Spins up a Testcontainers Postgres, launches the backend JAR as a
 * child process pointed at it, waits for {@code /actuator/health} UP,
 * then runs the simulator's {@code scenario} mode against the §5.3 seed
 * CSV and asserts the §5.4 expected book.
 *
 * <p>The backend doesn't expose a Maven artifact the simulator depends
 * on (architecture: simulator hits public APIs only — never imports
 * backend code). So the JAR is resolved by relative path from the
 * workspace; if the backend hasn't been built, the test is skipped via
 * {@link org.junit.jupiter.api.Assumptions#assumeTrue}. CI must run
 * {@code (cd backend && ./mvnw -DskipTests package)} before
 * {@code (cd simulator && ./mvnw test)} for this test to actually exercise.
 *
 * <p>Named {@code *IT.java} so it slots into the failsafe phase if a
 * future pom switches to it; today it runs under surefire and the
 * skip-via-assume keeps the unit-test fast path clean when the
 * dependent JAR is absent.
 */
@Testcontainers
class BackendIntegrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("lob")
            .withUsername("lob")
            .withPassword("lob");

    private static Process backend;
    private static int backendPort;
    private static Path backendJar;

    @BeforeAll
    static void startBackend() throws Exception {
        // Resolve backend JAR relative to the simulator module's working
        // directory: <repo>/backend/target/lob-backend-0.1.0-SNAPSHOT.jar
        Path repoRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().getParent();
        backendJar = repoRoot.resolve("backend/target/lob-backend-0.1.0-SNAPSHOT.jar");
        assumeTrue(Files.exists(backendJar),
                "backend JAR not built — run (cd backend && ./mvnw -DskipTests package) first");

        backendPort = freePort();
        ProcessBuilder pb = new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", backendJar.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(
                        Path.of(System.getProperty("java.io.tmpdir"),
                                "backend-it-" + UUID.randomUUID() + ".log").toFile()));
        pb.environment().put("SPRING_DATASOURCE_URL", POSTGRES.getJdbcUrl());
        pb.environment().put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        pb.environment().put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        pb.environment().put("SERVER_PORT", String.valueOf(backendPort));
        // Stable JWT secret — only this test uses it; never wired to a real env.
        pb.environment().put("JWT_SIGNING_SECRET",
                "integration-test-jwt-signing-secret-which-is-256-bits-long-no-really");
        pb.environment().put("INSTANCE_ID", "backend-it");

        backend = pb.start();
        waitForHealthUp(Duration.ofSeconds(60));
    }

    @AfterAll
    static void stopBackend() throws Exception {
        if (backend != null && backend.isAlive()) {
            backend.destroy();
            if (!backend.waitFor(20, TimeUnit.SECONDS)) {
                // Spring Boot occasionally lingers on shutdown; SIGKILL is fine here.
                backend.destroyForcibly();
            }
        }
    }

    @Test
    void seedCsvReplayProducesExpectedBook() throws IOException {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().getParent();
        Path seedCsv = repoRoot.resolve("docs/requirnments/seed.csv");
        Path expected = repoRoot.resolve("docs/requirnments/seed-expected-book.json");
        assumeTrue(Files.exists(seedCsv), "seed.csv not present at " + seedCsv);
        assumeTrue(Files.exists(expected), "seed-expected-book.json not present at " + expected);

        String baseUrl = "http://localhost:" + backendPort;
        LobApiClient api = new LobApiClient(baseUrl);
        ScenarioRunner runner = new ScenarioRunner(api, new TokenCache(), SeedCredentials.defaults());

        RunReport report = runner.run(seedCsv, expected, ScenarioRunner.newRunId());

        assertThat(report.submitted).isEqualTo(10);
        assertThat(report.accepted).isEqualTo(10);
        assertThat(report.rejected).isZero();
        // §5.4 asserts five symbols (AAPL, MSFT, TSLA, GOOGL, AMZN).
        assertThat(report.assertions).hasSize(5);
        assertThat(report.allAssertionsPassed())
                .as("all book:* assertions must pass — %s", report.assertions)
                .isTrue();
    }

    // ---------- helpers ----------

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /**
     * Polls {@code /actuator/health} until it returns 200 + a body containing
     * "UP", or the deadline elapses. The backend's full cold start (Flyway
     * migrate + LISTEN connection) typically lands well under 30 s; the 60 s
     * budget leaves headroom for slow CI hosts.
     */
    private static void waitForHealthUp(Duration deadline) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        long until = System.nanoTime() + deadline.toNanos();
        Exception last = null;
        while (System.nanoTime() < until) {
            try {
                HttpResponse<String> r = http.send(
                        HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + backendPort + "/actuator/health"))
                                .timeout(Duration.ofSeconds(2))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200 && r.body().contains("\"status\":\"UP\"")) return;
            } catch (Exception e) {
                last = e;
            }
            if (!backend.isAlive()) {
                throw new IllegalStateException(
                        "backend process died during startup; exit=" + backend.exitValue()
                                + " (logs in java.io.tmpdir/backend-it-*.log)");
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("backend did not become healthy within " + deadline, last);
    }
}
