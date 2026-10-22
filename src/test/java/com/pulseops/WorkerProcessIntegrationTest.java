package com.pulseops;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
class WorkerProcessIntegrationTest {
    private String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
    private Connection database() throws Exception {
        return DriverManager.getConnection(env("TEST_DATABASE_URL", "jdbc:postgresql://localhost:5432/pulseops_test"),
            env("TEST_DATABASE_USERNAME", "pulseops"), env("TEST_DATABASE_PASSWORD", "pulseops-local"));
    }
    private Process start(String name) throws Exception {
        Path log = Path.of("build", "worker-process-" + name + ".log");
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", Path.of("build/libs/app.jar").toAbsolutePath().toString(), "--spring.profiles.active=worker");
        builder.environment().put("DB_URL", env("TEST_DATABASE_URL", "jdbc:postgresql://localhost:5432/pulseops_test"));
        builder.environment().put("DB_USERNAME", env("TEST_DATABASE_USERNAME", "pulseops"));
        builder.environment().put("DB_PASSWORD", env("TEST_DATABASE_PASSWORD", "pulseops-local"));
        return builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private void assertExited(Process process, String name) throws Exception {
        try {
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("Worker should terminate").isTrue();
            String log = Files.readString(Path.of("build", "worker-process-" + name + ".log"));
            assertThat(process.exitValue()).withFailMessage(log).isZero();
            assertThat(log).doesNotContain("Tomcat started").contains("Shutdown completed");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    @Test void executableJarWorkerExitsAndConcurrentProcessesDoNotDuplicate() throws Exception {
        // Bootstrap/migrate even if this test class runs before the Spring integration tests.
        Process bootstrap = start("bootstrap"); assertExited(bootstrap, "bootstrap");
        try (Connection c = database(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE sites RESTART IDENTITY CASCADE");
            // Insert a blocked destination directly: exercises real worker failure persistence without external traffic.
            s.execute("INSERT INTO sites(name,url,interval_seconds,enabled,created_at,next_check_at) VALUES "
                + "('Blocked fixture','https://127.0.0.1/',60,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP - INTERVAL '1 minute')");
        }
        Process first = start("first"), second = start("second");
        try { assertExited(first, "first"); assertExited(second, "second"); }
        finally { if (first.isAlive()) first.destroyForcibly(); if (second.isAlive()) second.destroyForcibly(); }
        try (Connection c = database(); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT count(*), min(status), max(http_status_code), max(response_time_ms) FROM check_results")) {
                r.next(); assertThat(r.getInt(1)).isEqualTo(1); assertThat(r.getString(2)).isEqualTo("DOWN");
                assertThat(r.getObject(3)).isNull(); assertThat(r.getObject(4)).isNull();
            }
            try (ResultSet r = s.executeQuery("SELECT next_check_at > CURRENT_TIMESTAMP FROM sites")) {
                r.next(); assertThat(r.getBoolean(1)).isTrue();
            }
            s.execute("TRUNCATE sites RESTART IDENTITY CASCADE");
        }
    }
}
