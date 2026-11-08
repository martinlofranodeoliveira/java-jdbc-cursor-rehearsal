# Changelog

All notable changes to `java-jdbc-cursor-rehearsal` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-11-08

### Added
- Isolated `PostgresClusterManager` providing ephemeral, unprivileged PostgreSQL 17 cluster fixture initialization (`initdb`, `postgres`, `pg_isready`).
- `LoopbackRelay` proxy providing response withholding and TCP RST (`SO_LINGER=0`) simulation without requiring OS-level iptables or root capabilities.
- 8 comprehensive protocol rehearsal scenarios in `CursorRehearsalLab`:
  - Scenario 1: Server portal creation and batching locality verification via `pg_cursors`.
  - Scenario 2: Demand accounting and row-level advisory lock barriers (`pg_locks`).
  - Scenario 3: Non-cursor controls verifying upfront evaluation under `autocommit=true` and `fetchSize=0`.
  - Scenario 4: Initial batch `Statement.cancel()` and `setQueryTimeout()` yielding SQLState `57014` and clean same-session recovery.
  - Scenario 5: Subsequent fetch cancellation demonstrating driver state `IDLE` requiring `PGConnection.cancelQuery()`.
  - Scenario 6: Nonholdable portal destruction across transaction `commit` and `rollback` boundaries.
  - Scenario 7: Native socket timeout (`socketTimeout=2s`) handling via relay response withholding and connection discard.
  - Scenario 8: Transport RST socket closure detection and safe connection discard.
- JUnit Jupiter 6 test suite covering all 8 scenarios.
- Standalone CLI executable supporting `--assert`, `--json`, and `--help`.
- Offline qualification scripts `scripts/test-offline.sh` and `scripts/demo-offline.sh`.
