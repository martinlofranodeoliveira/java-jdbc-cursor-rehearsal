package com.lofranotecnologia.jdbc;

import java.util.List;

/**
 * Command-line entry point for PostgreSQL JDBC Cursor & Cancellation Rehearsal Lab.
 */
public final class JdbcCursorRehearsalApp {

    public static void main(String[] args) {
        boolean assertMode = false;
        boolean jsonMode = false;

        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                System.exit(0);
            } else if ("--assert".equals(arg)) {
                assertMode = true;
            } else if ("--json".equals(arg)) {
                jsonMode = true;
            }
        }

        if (!assertMode && !jsonMode) {
            assertMode = true; // Default to running rehearsal with assertion
        }

        try (PostgresClusterManager cluster = new PostgresClusterManager()) {
            if (!jsonMode) {
                System.out.println("Initializing private PostgreSQL 17 cluster fixture...");
            }
            cluster.start();
            if (!jsonMode) {
                System.out.println("PostgreSQL cluster ready on port " + cluster.getPort());
                System.out.println("Running JDBC Cursor & Cancellation Rehearsal scenarios...\n");
            }

            CursorRehearsalLab lab = new CursorRehearsalLab(cluster);
            List<CursorRehearsalLab.ScenarioResult> results = lab.runAllScenarios();

            boolean allPassed = true;
            for (CursorRehearsalLab.ScenarioResult r : results) {
                if (!r.passed()) {
                    allPassed = false;
                }
            }

            if (jsonMode) {
                System.out.println("{");
                System.out.println("  \"application\": \"java-jdbc-cursor-rehearsal\",");
                System.out.println("  \"version\": \"0.1.0\",");
                System.out.println("  \"all_passed\": " + allPassed + ",");
                System.out.println("  \"scenarios\": [");
                for (int i = 0; i < results.size(); i++) {
                    CursorRehearsalLab.ScenarioResult r = results.get(i);
                    String comma = (i < results.size() - 1) ? "," : "";
                    System.out.println("    {");
                    System.out.println("      \"name\": \"" + escapeJson(r.name()) + "\",");
                    System.out.println("      \"passed\": " + r.passed() + ",");
                    System.out.println("      \"details\": \"" + escapeJson(r.details()) + "\"");
                    System.out.println("    }" + comma);
                }
                System.out.println("  ]");
                System.out.println("}");
            } else {
                System.out.println("================================================================================");
                System.out.printf("%-55s | %-6s | %s%n", "SCENARIO", "STATUS", "DETAILS");
                System.out.println("--------------------------------------------------------------------------------");
                for (CursorRehearsalLab.ScenarioResult r : results) {
                    String status = r.passed() ? "PASS" : "FAIL";
                    System.out.printf("%-55s | %-6s | %s%n", r.name(), status, r.details());
                }
                System.out.println("================================================================================");
                if (allPassed) {
                    System.out.println("\nAll " + results.size() + " protocol rehearsal scenarios passed successfully.");
                } else {
                    System.err.println("\nOne or more protocol scenarios failed!");
                    System.exit(1);
                }
            }

            if (!allPassed) {
                System.exit(1);
            }
        } catch (Throwable t) {
            System.err.println("Rehearsal failed with unhandled exception: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void printHelp() {
        System.out.println("JDBC Cursor and Cancellation Rehearsal Lab (Java 21 / PostgreSQL 17)");
        System.out.println("Usage: java -jar java-jdbc-cursor-rehearsal.jar [OPTIONS]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --assert   Run all 8 protocol scenarios and assert complete protocol compliance (exit 0 on success)");
        System.out.println("  --json     Emit machine-readable JSON report of all scenarios");
        System.out.println("  --help     Show this help message and exit");
    }
}
