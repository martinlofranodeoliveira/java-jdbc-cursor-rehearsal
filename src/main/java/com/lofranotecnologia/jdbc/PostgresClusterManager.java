package com.lofranotecnologia.jdbc;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Manages an isolated private PostgreSQL cluster on 127.0.0.1 for deterministic testing.
 */
public final class PostgresClusterManager implements AutoCloseable {
    private final Path tempDir;
    private final Path dataDir;
    private final Path socketDir;
    private final int port;
    private Process postmasterProcess;

    public PostgresClusterManager() throws IOException {
        this.tempDir = Files.createTempDirectory("pg-cluster-");
        this.dataDir = tempDir.resolve("data");
        this.socketDir = tempDir.resolve("socket");
        Files.createDirectories(socketDir);
        this.port = findFreePort();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    public int getPort() {
        return port;
    }

    public void start() throws Exception {
        // 1. initdb
        ProcessBuilder initBuilder = new ProcessBuilder(
                "initdb",
                "-D", dataDir.toString(),
                "-U", "fixture",
                "-A", "trust",
                "--no-locale",
                "--encoding=UTF8",
                "--no-instructions"
        );
        initBuilder.redirectErrorStream(true);
        initBuilder.environment().put("HOME", "/tmp");
        Process initProc = initBuilder.start();
        if (!initProc.waitFor(30, TimeUnit.SECONDS) || initProc.exitValue() != 0) {
            throw new IllegalStateException("initdb failed with exit code " + initProc.exitValue());
        }

        // 2. start postgres daemon
        ProcessBuilder pgBuilder = new ProcessBuilder(
                "postgres",
                "-D", dataDir.toString(),
                "-p", String.valueOf(port),
                "-h", "127.0.0.1",
                "-k", socketDir.toString(),
                "-c", "shared_buffers=16MB",
                "-c", "work_mem=1MB",
                "-c", "max_connections=16",
                "-c", "max_worker_processes=0",
                "-c", "max_parallel_workers=0",
                "-c", "max_parallel_workers_per_gather=0",
                "-c", "autovacuum=off",
                "-c", "jit=off",
                "-c", "fsync=off",
                "-c", "timezone=UTC"
        );
        pgBuilder.redirectOutput(tempDir.resolve("postgres.log").toFile());
        pgBuilder.redirectErrorStream(true);
        pgBuilder.environment().put("HOME", "/tmp");
        this.postmasterProcess = pgBuilder.start();

        // 3. wait for pg_isready
        long deadline = System.currentTimeMillis() + 15000;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            if (!postmasterProcess.isAlive()) {
                throw new IllegalStateException("Postgres process exited unexpectedly");
            }
            Process isReady = new ProcessBuilder(
                    "pg_isready",
                    "-h", "127.0.0.1",
                    "-p", String.valueOf(port),
                    "-U", "fixture",
                    "-d", "postgres",
                    "-t", "1"
            ).start();
            if (isReady.waitFor(2, TimeUnit.SECONDS) && isReady.exitValue() == 0) {
                ready = true;
                break;
            }
            Thread.sleep(50);
        }

        if (!ready) {
            throw new IllegalStateException("Postgres failed to become ready within deadline");
        }

        // 4. initialize fixture schema and helper functions
        try (Connection conn = createConnection("init", 10);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SEQUENCE progress AS integer START WITH 1 INCREMENT BY 1 NO CYCLE CACHE 1");

            stmt.execute("""
                CREATE OR REPLACE FUNCTION fixture_touch(row_val integer, lock_at integer)
                RETURNS integer AS $$
                BEGIN
                    PERFORM nextval('progress');
                    IF lock_at > 0 AND row_val = lock_at THEN
                        PERFORM pg_advisory_lock(64241631);
                    END IF;
                    RETURN row_val;
                END;
                $$ LANGUAGE plpgsql
            """);

            stmt.execute("""
                CREATE OR REPLACE FUNCTION fixture_touch_key(row_val integer, lock_at integer, lock_key bigint)
                RETURNS integer AS $$
                BEGIN
                    PERFORM nextval('progress');
                    IF lock_at > 0 AND row_val = lock_at THEN
                        PERFORM pg_advisory_lock(lock_key);
                    END IF;
                    RETURN row_val;
                END;
                $$ LANGUAGE plpgsql
            """);
        }
    }

    public Connection createConnection(String appName, int socketTimeoutSeconds) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "fixture");
        props.setProperty("ApplicationName", appName);
        props.setProperty("sslmode", "disable");
        props.setProperty("gssEncMode", "disable");
        props.setProperty("connectTimeout", "2");
        props.setProperty("socketTimeout", String.valueOf(socketTimeoutSeconds));
        props.setProperty("cancelSignalTimeout", "2");

        String url = "jdbc:postgresql://127.0.0.1:" + port + "/postgres";
        Connection conn = DriverManager.getConnection(url, props);
        try (Statement s = conn.createStatement()) {
            s.execute("SET statement_timeout='10s'");
        }
        return conn;
    }

    public Connection createConnectionViaRelay(int relayPort, String appName, int socketTimeoutSeconds) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", "fixture");
        props.setProperty("ApplicationName", appName);
        props.setProperty("sslmode", "disable");
        props.setProperty("gssEncMode", "disable");
        props.setProperty("connectTimeout", "2");
        props.setProperty("socketTimeout", String.valueOf(socketTimeoutSeconds));
        props.setProperty("cancelSignalTimeout", "2");

        String url = "jdbc:postgresql://127.0.0.1:" + relayPort + "/postgres";
        Connection conn = DriverManager.getConnection(url, props);
        try (Statement s = conn.createStatement()) {
            s.execute("SET statement_timeout='10s'");
        }
        return conn;
    }

    @Override
    public void close() {
        if (postmasterProcess != null && postmasterProcess.isAlive()) {
            postmasterProcess.destroy(); // SIGTERM
            try {
                if (!postmasterProcess.waitFor(5, TimeUnit.SECONDS)) {
                    postmasterProcess.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                postmasterProcess.destroyForcibly();
            }
        }
        deleteRecursively(tempDir.toFile());
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursively(f);
                }
            }
        }
        file.delete();
    }
}
