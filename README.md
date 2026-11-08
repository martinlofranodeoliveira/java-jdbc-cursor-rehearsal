# JDBC Cursor and Cancellation Rehearsal

[![CI](https://github.com/martinlofranodeoliveira/java-jdbc-cursor-rehearsal/actions/workflows/ci.yml/badge.svg)](https://github.com/martinlofranodeoliveira/java-jdbc-cursor-rehearsal/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net)
[![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-blue.svg)](https://www.postgresql.org/)

An empirical protocol validation lab and diagnostic suite designed to test, verify, and understand PostgreSQL server-side cursor management and JDBC statement cancellation under real network conditions.

## Key Features

1. **Server-Side Portal Lifecycle**: Verifies exact conditions required to create and retain server-side portals in `pg_cursors`.
2. **Session Locality Verification**: Demonstrates that PostgreSQL `pg_cursors` entries are private to the owning session and invisible to external observer connections.
3. **Demand Accounting & Lock Barriers**: Distinguishes row evaluation from consumer consumption using PL/pgSQL advisory lock barriers and strict sequence accounting.
4. **Driver State Transitions**: Exposes why `Statement.cancel()` is a no-op during subsequent cursor batch fetches (due to driver state transitioning to `IDLE`) and proves that `PGConnection.cancelQuery()` successfully aborts blocked fetches with SQLState `57014`.
5. **Transactional Boundaries**: Proves nonholdable portal closure upon `commit` and `rollback` operations.
6. **Fault Injection without Root**: Utilizes an unprivileged loopback TCP relay to test client-side socket read timeouts and TCP RST connection poisoning.

## Scenarios

| # | Scenario | Verification Focus |
|---|----------|-------------------|
| 1 | Server Portal Creation & Batching Locality | Verifies 4-row batching, `pg_cursors` entry in same session, and absence in observer session. |
| 2 | Demand Accounting & Lock Barrier | Observes row 5 evaluation pause via `pg_locks` before consumer requests batch 2. |
| 3 | Non-Cursor Controls | Proves upfront evaluation of all rows when `autocommit=true` or `fetchSize=0`. |
| 4 | Initial Batch Cancellation & Query Timeout | Proves `Statement.cancel()` and `setQueryTimeout(1)` abort initial queries with SQLState `57014`. |
| 5 | Later Fetch Cancellation via PGConnection | Proves statement state is `IDLE` during later fetches; cancels via `PGConnection.cancelQuery()`. |
| 6 | Commit & Rollback Boundaries | Demonstrates nonholdable cursor invalidation upon transaction completion and clean recovery. |
| 7 | Native Socket Timeout via Relay | Injects server-side withholding, triggering client `socketTimeout` and connection discard. |
| 8 | Transport RST & Connection Poisoning | Simulates TCP RST (`SO_LINGER=0`), catching transport failure and enforcing connection discard. |

## Requirements
- Java 21+ (OpenJDK / Eclipse Temurin)
- Apache Maven 3.9+
- PostgreSQL 16+ or 17+ client and server utilities (`initdb`, `postgres`, `pg_isready`)

## Building and Running

### Build Standalone Executable
```bash
mvn clean package -DskipTests
```

### Run Rehearsal with CLI Assertions
```bash
java -jar target/java-jdbc-cursor-rehearsal-0.1.0-standalone.jar --assert
```

### Run Rehearsal with JSON Report
```bash
java -jar target/java-jdbc-cursor-rehearsal-0.1.0-standalone.jar --json
```

### Run Offline Test Suite
```bash
/bin/sh scripts/test-offline.sh
```

### Run Offline Demo
```bash
/bin/sh scripts/demo-offline.sh --assert
```

## License
MIT License. Copyright (c) 2026 Lofrano Tecnologia.
