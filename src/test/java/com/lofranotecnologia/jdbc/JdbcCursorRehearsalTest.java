package com.lofranotecnologia.jdbc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JdbcCursorRehearsalTest {
    private static PostgresClusterManager cluster;
    private static CursorRehearsalLab lab;

    @BeforeAll
    public static void setUp() throws Exception {
        cluster = new PostgresClusterManager();
        cluster.start();
        lab = new CursorRehearsalLab(cluster);
    }

    @AfterAll
    public static void tearDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    @DisplayName("Scenario 1: Server Portal Creation & Batching Locality")
    public void testScenario1() {
        CursorRehearsalLab.ScenarioResult res = lab.scenario1_server_portal_and_batching();
        assertTrue(res.passed(), res.details());
    }

    @Test
    @DisplayName("Scenario 2: Demand Accounting & Acknowledged Barrier")
    public void testScenario2() {
        CursorRehearsalLab.ScenarioResult res = lab.scenario2_demand_accounting_and_barrier();
        assertTrue(res.passed(), res.details());
    }

    @Test
    @DisplayName("Scenario 3: Non-Cursor Controls (Autocommit true & fetchSize 0)")
    public void testScenario3() {
        CursorRehearsalLab.ScenarioResult res = lab.scenario3_non_cursor_controls();
        assertTrue(res.passed(), res.details());
    }

    @Test
    @DisplayName("Scenario 4: Initial Batch Statement.cancel() & Query Timeout")
    public void testScenario4() {
        CursorRehearsalLab.ScenarioResult res = lab.scenario4_initial_cancellation_and_query_timeout();
        assertTrue(res.passed(), res.details());
    }

    @Test
    @DisplayName("Scenario 5: Later Fetch Cancellation via PGConnection.cancelQuery()")
    public void testScenario5() {
        CursorRehearsalLab.ScenarioResult res = lab.scenario5_later_fetch_cancellation();
        assertTrue(res.passed(), res.details());
    }

    @Test
    @DisplayName("Scenario 6: Commit and Rollback Boundaries on Nonholdable Portal")
    public void testScenario6() {
        CursorRehearsalLab.ScenarioResult res = lab.scenario6_commit_and_rollback_boundaries();
        assertTrue(res.passed(), res.details());
    }

    @Test
    @DisplayName("Scenario 7: Native Socket Timeout via Response Withholding Relay")
    public void testScenario7() {
        CursorRehearsalLab.ScenarioResult res = lab.scenario7_socket_timeout_with_relay();
        assertTrue(res.passed(), res.details());
    }

    @Test
    @DisplayName("Scenario 8: Transport RST & Connection Poisoning Discard")
    public void testScenario8() {
        CursorRehearsalLab.ScenarioResult res = lab.scenario8_relay_reset_connection_poisoning();
        assertTrue(res.passed(), res.details());
    }
}
