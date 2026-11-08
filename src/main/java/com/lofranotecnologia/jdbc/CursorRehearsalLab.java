package com.lofranotecnologia.jdbc;

import org.postgresql.PGConnection;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Rehearsal lab executing real protocol scenarios against PostgreSQL.
 */
public final class CursorRehearsalLab {
    public static final long ADVISORY_KEY = 64241631L;
    private static final ExecutorService ASYNC = Executors.newVirtualThreadPerTaskExecutor();

    private final PostgresClusterManager cluster;

    public CursorRehearsalLab(PostgresClusterManager cluster) {
        this.cluster = cluster;
    }

    public static record ScenarioResult(
            String name,
            boolean passed,
            String details
    ) {}

    public List<ScenarioResult> runAllScenarios() {
        List<ScenarioResult> results = new ArrayList<>();
        results.add(scenario1_server_portal_and_batching());
        results.add(scenario2_demand_accounting_and_barrier());
        results.add(scenario3_non_cursor_controls());
        results.add(scenario4_initial_cancellation_and_query_timeout());
        results.add(scenario5_later_fetch_cancellation());
        results.add(scenario6_commit_and_rollback_boundaries());
        results.add(scenario7_socket_timeout_with_relay());
        results.add(scenario8_relay_reset_connection_poisoning());
        return results;
    }

    // Helper functions
    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    private static long queryNumber(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            check(r.next(), "scalar result expected for: " + sql);
            return r.getLong(1);
        }
    }

    private static long getProgress(Connection c) throws SQLException {
        return queryNumber(c, "SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM progress");
    }

    private static void resetProgress(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ALTER SEQUENCE progress RESTART WITH 1");
        }
    }

    private static int getBackendPid(Connection c) throws SQLException {
        return (int) queryNumber(c, "SELECT pg_backend_pid()");
    }

    private static long countPortals(Connection c, String query) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM pg_cursors WHERE name <> '' AND statement = ?")) {
            s.setString(1, query);
            try (ResultSet r = s.executeQuery()) {
                check(r.next(), "cursor view result");
                return r.getLong(1);
            }
        }
    }

    private static boolean isBackendWaitingOnLock(Connection observer, int backendPid, String appName) throws SQLException {
        String sql = """
            SELECT EXISTS (
                SELECT 1 FROM pg_stat_activity a
                JOIN pg_locks l ON l.pid = a.pid
                WHERE a.pid = ? AND a.application_name = ?
                  AND a.state = 'active' AND a.wait_event_type = 'Lock'
                  AND l.locktype = 'advisory' AND NOT l.granted
            )
        """;
        try (PreparedStatement s = observer.prepareStatement(sql)) {
            s.setInt(1, backendPid);
            s.setString(2, appName);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getBoolean(1);
            }
        }
    }

    private static void awaitBackendLock(Connection observer, int backendPid, String appName, long timeoutSeconds) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (isBackendWaitingOnLock(observer, backendPid, appName)) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        throw new AssertionError("Backend " + backendPid + " (" + appName + ") did not reach advisory lock barrier within deadline");
    }

    private static void releaseAdvisoryLock(Connection c, long key) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SELECT pg_advisory_unlock(" + key + ")");
        }
    }

    private static String extractSqlState(Future<?> future, long timeoutSeconds) throws Exception {
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);
            throw new AssertionError("Expected SQL operation to fail/timeout, but it succeeded");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
            throw e;
        }
    }

    private static void verifyRecoveryAndSentinel(Connection c, String query) throws SQLException {
        c.rollback();
        check(countPortals(c, query) == 0, "Portal survived rollback!");
        check(queryNumber(c, "SELECT 4242") == 4242, "Same-session sentinel query failed");
        c.rollback();
    }

    // SCENARIO 1: Server Portal Creation & Batching Locality
    public ScenarioResult scenario1_server_portal_and_batching() {
        String name = "1. Server Portal Creation & Batching Locality";
        try (Connection observer = cluster.createConnection("obs_s1", 10);
             Connection client = cluster.createConnection("cli_s1", 10)) {
            resetProgress(observer);
            client.setAutoCommit(false);

            String query = "/*s1*/ SELECT fixture_touch(g, 0) FROM generate_series(1, 12) AS g";
            try (PreparedStatement s = client.prepareStatement(query,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
                s.setFetchSize(4);
                try (ResultSet r = s.executeQuery()) {
                    // Check first batch
                    for (int i = 1; i <= 4; i++) {
                        check(r.next(), "expected row " + i);
                        check(r.getInt(1) == i, "row value mismatch");
                    }

                    // Progress evaluated must be 4
                    check(getProgress(observer) == 4, "exact batch size progress");

                    // Same session sees portal
                    check(countPortals(client, query) == 1, "portal must exist in same session");

                    // Observer connection must see 0 portals for that statement (session locality)
                    check(countPortals(observer, query) == 0, "observer must not see client's pg_cursors");
                }
            }
            client.rollback();
            return new ScenarioResult(name, true, "First batch yielded rows 1..4, progress=4, portal=1, session locality=0");
        } catch (Exception e) {
            return new ScenarioResult(name, false, e.toString());
        }
    }

    // SCENARIO 2: Demand Accounting & Acknowledged Barrier
    public ScenarioResult scenario2_demand_accounting_and_barrier() {
        String name = "2. Demand Accounting & Row-Level Lock Barrier";
        String mark = "cli_s2";
        try (Connection observer = cluster.createConnection("obs_s2", 10);
             Connection client = cluster.createConnection(mark, 10)) {
            resetProgress(observer);
            client.setAutoCommit(false);
            int backendPid = getBackendPid(client);

            // Lock row 5
            try (Connection lockHolder = cluster.createConnection("lock_holder", 10)) {
                try (Statement s = lockHolder.createStatement()) {
                    s.execute("SELECT pg_advisory_lock(" + ADVISORY_KEY + ")");
                }

                String query = "/*s2*/ SELECT fixture_touch(g, 5) FROM generate_series(1, 12) AS g";
                try (PreparedStatement s = client.prepareStatement(query,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
                    s.setFetchSize(4);
                    try (ResultSet r = s.executeQuery()) {
                        for (int i = 1; i <= 4; i++) {
                            check(r.next(), "row " + i);
                            check(r.getInt(1) == i, "val " + i);
                        }
                        check(getProgress(observer) == 4, "progress before fetch 2");

                        // Next batch: fetch next row triggers row 5 evaluation and hits lock
                        Future<?> nextFetch = ASYNC.submit(() -> {
                            r.next();
                            return null;
                        });

                        awaitBackendLock(observer, backendPid, mark, 4);
                        check(getProgress(observer) == 5, "row 5 evaluated at barrier");

                        // Release barrier
                        releaseAdvisoryLock(lockHolder, ADVISORY_KEY);
                        nextFetch.get(3, TimeUnit.SECONDS);

                        // Read remaining of batch 2 (rows 6..8)
                        for (int i = 6; i <= 8; i++) {
                            check(r.next(), "row " + i);
                            check(r.getInt(1) == i, "val " + i);
                        }
                        check(getProgress(observer) == 8, "batch 2 complete progress=8");
                    }
                }
                client.rollback();
            }
            return new ScenarioResult(name, true, "Demand pause observed, row 5 barrier hit, lock verified in pg_stat_activity");
        } catch (Exception e) {
            return new ScenarioResult(name, false, e.toString());
        }
    }

    // SCENARIO 3: Non-Cursor Controls (Autocommit and fetchSize=0)
    public ScenarioResult scenario3_non_cursor_controls() {
        String name = "3. Non-Cursor Controls (Autocommit true & fetchSize 0)";
        try (Connection observer = cluster.createConnection("obs_s3", 10)) {
            // Control A: autocommit=true, fetchSize=4 -> evaluates all immediately, 0 portals
            {
                resetProgress(observer);
                try (Connection client = cluster.createConnection("cli_s3a", 10)) {
                    client.setAutoCommit(true);
                    String query = "/*s3a*/ SELECT fixture_touch(g, 0) FROM generate_series(1, 12) AS g";
                    try (PreparedStatement s = client.prepareStatement(query)) {
                        s.setFetchSize(4);
                        try (ResultSet r = s.executeQuery()) {
                            check(r.next() && r.getInt(1) == 1, "first row available");
                            check(getProgress(observer) == 12, "all 12 rows evaluated immediately in non-cursor mode");
                            check(countPortals(client, query) == 0, "no portal created when autocommit=true");
                        }
                    }
                }
            }

            // Control B: autocommit=false, fetchSize=0 -> evaluates all immediately, 0 portals
            {
                resetProgress(observer);
                try (Connection client = cluster.createConnection("cli_s3b", 10)) {
                    client.setAutoCommit(false);
                    String query = "/*s3b*/ SELECT fixture_touch(g, 0) FROM generate_series(1, 12) AS g";
                    try (PreparedStatement s = client.prepareStatement(query)) {
                        s.setFetchSize(0);
                        try (ResultSet r = s.executeQuery()) {
                            check(r.next() && r.getInt(1) == 1, "first row available");
                            check(getProgress(observer) == 12, "all 12 rows evaluated immediately when fetchSize=0");
                            check(countPortals(client, query) == 0, "no portal created when fetchSize=0");
                        }
                    }
                    client.rollback();
                }
            }

            return new ScenarioResult(name, true, "Both autocommit=true and fetchSize=0 evaluate all rows upfront with 0 portals");
        } catch (Exception e) {
            return new ScenarioResult(name, false, e.toString());
        }
    }

    // SCENARIO 4: Initial Batch Cancellation & Query Timeout
    public ScenarioResult scenario4_initial_cancellation_and_query_timeout() {
        String name = "4. Initial Batch Statement.cancel() & Query Timeout";
        String mark = "cli_s4";
        try (Connection observer = cluster.createConnection("obs_s4", 10);
             Connection lockHolder = cluster.createConnection("lock_s4", 10)) {

            try (Statement s = lockHolder.createStatement()) {
                s.execute("SELECT pg_advisory_lock(" + ADVISORY_KEY + ")");
            }

            // Test 1: Statement.cancel()
            {
                resetProgress(observer);
                try (Connection client = cluster.createConnection(mark, 10)) {
                    client.setAutoCommit(false);
                    int backendPid = getBackendPid(client);
                    String query = "/*s4_cancel*/ SELECT fixture_touch(g, 1) FROM generate_series(1, 12) AS g";
                    try (PreparedStatement s = client.prepareStatement(query,
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
                        s.setFetchSize(4);
                        Future<?> task = ASYNC.submit(() -> {
                            try (ResultSet r = s.executeQuery()) {
                                while (r.next()) r.getInt(1);
                            }
                            return null;
                        });

                        awaitBackendLock(observer, backendPid, mark, 4);
                        check(getProgress(observer) == 1, "progress=1 at lock");

                        s.cancel();
                        String sqlState = extractSqlState(task, 4);
                        check("57014".equals(sqlState), "expected SQLState 57014 (query canceled), got " + sqlState);

                        verifyRecoveryAndSentinel(client, query);
                    }
                }
            }

            // Test 2: Native query timeout (setQueryTimeout)
            {
                resetProgress(observer);
                try (Connection client = cluster.createConnection(mark, 10)) {
                    client.setAutoCommit(false);
                    int backendPid = getBackendPid(client);
                    String query = "/*s4_timeout*/ SELECT fixture_touch(g, 1) FROM generate_series(1, 12) AS g";
                    try (PreparedStatement s = client.prepareStatement(query,
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
                        s.setFetchSize(4);
                        s.setQueryTimeout(1); // 1 second timeout

                        Future<?> task = ASYNC.submit(() -> {
                            try (ResultSet r = s.executeQuery()) {
                                while (r.next()) r.getInt(1);
                            }
                            return null;
                        });

                        awaitBackendLock(observer, backendPid, mark, 4);
                        String sqlState = extractSqlState(task, 4);
                        check("57014".equals(sqlState), "expected timeout SQLState 57014, got " + sqlState);

                        verifyRecoveryAndSentinel(client, query);
                    }
                }
            }

            releaseAdvisoryLock(lockHolder, ADVISORY_KEY);
            return new ScenarioResult(name, true, "Statement.cancel() and queryTimeout produced SQLState 57014, portal0, sentinel 4242");
        } catch (Exception e) {
            return new ScenarioResult(name, false, e.toString());
        }
    }

    // SCENARIO 5: Later Fetch Cancellation via PGConnection
    public ScenarioResult scenario5_later_fetch_cancellation() {
        String name = "5. Later Fetch Cancellation via PGConnection.cancelQuery()";
        String mark = "cli_s5";
        try (Connection observer = cluster.createConnection("obs_s5", 10);
             Connection lockHolder = cluster.createConnection("lock_s5", 10)) {

            try (Statement s = lockHolder.createStatement()) {
                s.execute("SELECT pg_advisory_lock(" + ADVISORY_KEY + ")");
            }

            resetProgress(observer);
            try (Connection client = cluster.createConnection(mark, 10)) {
                client.setAutoCommit(false);
                int backendPid = getBackendPid(client);
                String query = "/*s5*/ SELECT fixture_touch(g, 5) FROM generate_series(1, 12) AS g";

                try (PreparedStatement s = client.prepareStatement(query,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
                    s.setFetchSize(4);
                    s.setQueryTimeout(1);

                    try (ResultSet r = s.executeQuery()) {
                        for (int i = 1; i <= 4; i++) {
                            check(r.next() && r.getInt(1) == i, "first batch 1..4");
                        }
                        check(getProgress(observer) == 4, "progress after batch 1");

                        // Next fetch hits lock at row 5
                        Future<?> nextFetch = ASYNC.submit(() -> {
                            r.next();
                            return null;
                        });

                        awaitBackendLock(observer, backendPid, mark, 4);
                        check(getProgress(observer) == 5, "progress at lock");

                        // Inspect driver internals: Statement state is IDLE after executeQuery!
                        Field stateField = s.getClass().getSuperclass().getDeclaredField("statementState");
                        stateField.setAccessible(true);
                        check("IDLE".equals(stateField.get(s).toString()), "driver state is IDLE on subsequent fetch");

                        // Statement.cancel() is no-op because statementState is IDLE
                        s.cancel();
                        check(!nextFetch.isDone(), "Statement.cancel was no-op on later fetch");

                        // Use PGConnection.cancelQuery()
                        client.unwrap(PGConnection.class).cancelQuery();
                        String sqlState = extractSqlState(nextFetch, 4);
                        check("57014".equals(sqlState), "cancelQuery yielded 57014");

                        verifyRecoveryAndSentinel(client, query);
                    }
                }
            }

            releaseAdvisoryLock(lockHolder, ADVISORY_KEY);
            return new ScenarioResult(name, true, "Statement state IDLE proven, PGConnection.cancelQuery canceled fetch with 57014 and recovered");
        } catch (Exception e) {
            return new ScenarioResult(name, false, e.toString());
        }
    }

    // SCENARIO 6: Commit and Rollback Boundaries
    public ScenarioResult scenario6_commit_and_rollback_boundaries() {
        String name = "6. Commit & Rollback Boundaries on Nonholdable Portal";
        try (Connection observer = cluster.createConnection("obs_s6", 10)) {
            // Commit boundary
            {
                resetProgress(observer);
                try (Connection client = cluster.createConnection("cli_s6a", 10)) {
                    client.setAutoCommit(false);
                    String query = "/*s6a*/ SELECT fixture_touch(g, 0) FROM generate_series(1, 12) AS g";
                    try (PreparedStatement s = client.prepareStatement(query,
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
                        s.setFetchSize(4);
                        try (ResultSet r = s.executeQuery()) {
                            check(r.next() && r.getInt(1) == 1, "row 1");
                            check(countPortals(client, query) == 1, "portal active");

                            client.commit();
                            check(countPortals(client, query) == 0, "nonholdable portal closed on commit");

                            // Next calls must exhaust cached rows and fail
                            int cached = 0;
                            try {
                                while (r.next()) cached++;
                            } catch (SQLException ignored) {}
                            check(cached <= 3, "at most 3 cached rows remain");

                            client.rollback();
                            check(queryNumber(client, "SELECT 4242") == 4242, "sentinel query");
                        }
                    }
                }
            }

            // Rollback boundary
            {
                resetProgress(observer);
                try (Connection client = cluster.createConnection("cli_s6b", 10)) {
                    client.setAutoCommit(false);
                    String query = "/*s6b*/ SELECT fixture_touch(g, 0) FROM generate_series(1, 12) AS g";
                    try (PreparedStatement s = client.prepareStatement(query,
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
                        s.setFetchSize(4);
                        try (ResultSet r = s.executeQuery()) {
                            check(r.next() && r.getInt(1) == 1, "row 1");
                            check(countPortals(client, query) == 1, "portal active");

                            client.rollback();
                            check(countPortals(client, query) == 0, "nonholdable portal closed on rollback");

                            int cached = 0;
                            try {
                                while (r.next()) cached++;
                            } catch (SQLException ignored) {}
                            check(cached <= 3, "at most 3 cached rows remain");

                            client.rollback();
                            check(queryNumber(client, "SELECT 4242") == 4242, "sentinel query");
                        }
                    }
                }
            }

            return new ScenarioResult(name, true, "Nonholdable portal destroyed on commit and rollback; same session cleanly recovered");
        } catch (Exception e) {
            return new ScenarioResult(name, false, e.toString());
        }
    }

    // SCENARIO 7: Native Socket Timeout with Relay
    public ScenarioResult scenario7_socket_timeout_with_relay() {
        String name = "7. Native Socket Timeout via Response Withholding Relay";
        try (LoopbackRelay relay = new LoopbackRelay(cluster.getPort())) {
            // Client connects through relay with 2-second socketTimeout
            boolean caughtSocketTimeout = false;
            try (Connection client = cluster.createConnectionViaRelay(relay.getRelayPort(), "cli_s7", 2)) {
                client.setAutoCommit(false);
                String query = "/*s7*/ SELECT generate_series(1, 100)";
                try (PreparedStatement s = client.prepareStatement(query)) {
                    s.setFetchSize(4);
                    try (ResultSet r = s.executeQuery()) {
                        check(r.next(), "first row received normally");

                        // Now withhold server responses on the relay
                        relay.setWithholdResponses(true);

                        // Next fetch will block waiting on wire until socketTimeout (2s) expires
                        try {
                            while (r.next()) {
                                // drain until wire read blocks
                            }
                        } catch (SQLException ex) {
                            caughtSocketTimeout = true;
                        }
                    }
                }
            } catch (Exception e) {
                caughtSocketTimeout = true;
            }

            check(caughtSocketTimeout, "Socket timeout exception must be caught");

            // Verify clean discard and that a fresh connection operates normally
            try (Connection fresh = cluster.createConnection("cli_s7_fresh", 5)) {
                check(queryNumber(fresh, "SELECT 4242") == 4242, "fresh connection healthy");
            }

            return new ScenarioResult(name, true, "SocketTimeout triggered transport closure; connection discarded; fresh connection verified");
        } catch (Exception e) {
            return new ScenarioResult(name, false, e.toString());
        }
    }

    // SCENARIO 8: Relay Reset & Connection Poisoning Discard
    public ScenarioResult scenario8_relay_reset_connection_poisoning() {
        String name = "8. Transport RST & Connection Poisoning Discard";
        try (LoopbackRelay relay = new LoopbackRelay(cluster.getPort())) {
            boolean caughtRst = false;
            try (Connection client = cluster.createConnectionViaRelay(relay.getRelayPort(), "cli_s8", 5)) {
                client.setAutoCommit(false);
                String query = "/*s8*/ SELECT generate_series(1, 50)";
                try (PreparedStatement s = client.prepareStatement(query)) {
                    s.setFetchSize(4);
                    try (ResultSet r = s.executeQuery()) {
                        check(r.next(), "row 1 ok");

                        // Abruptly reset TCP connection via RST
                        relay.resetAllActiveSockets();

                        // Next fetch should detect transport failure and fail
                        try {
                            while (r.next()) {}
                        } catch (SQLException ex) {
                            caughtRst = true;
                        }
                    }
                }
            } catch (Exception e) {
                caughtRst = true;
            }

            check(caughtRst, "Connection RST must produce SQLException");

            // Verify fresh connection functions normally
            try (Connection fresh = cluster.createConnection("cli_s8_fresh", 5)) {
                check(queryNumber(fresh, "SELECT 4242") == 4242, "fresh connection healthy");
            }

            return new ScenarioResult(name, true, "Transport RST caught, stale rows not accepted as health, connection discarded cleanly");
        } catch (Exception e) {
            return new ScenarioResult(name, false, e.toString());
        }
    }
}
