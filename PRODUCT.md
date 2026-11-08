# Product Overview: JDBC Cursor and Cancellation Rehearsal

`java-jdbc-cursor-rehearsal` is an open-source protocol validation lab and diagnostic suite designed to test, verify, and understand PostgreSQL server-side cursor management and JDBC statement cancellation under real network conditions.

## Target Audience
- **Database Administrators & Platform Engineers**: Verify how PostgreSQL server-side portals (`pg_cursors`), backend state (`pg_stat_activity`), and lock trees (`pg_locks`) behave under JDBC connection pool backpressure.
- **Java / Backend Engineers**: Gain an empirical, protocol-grounded understanding of why `Statement.cancel()` behaves as a no-op during subsequent cursor batch fetches, and how to reliably cancel long-running server fetches via `PGConnection.cancelQuery()`.
- **Reliability Engineers**: Test socket timeout and TCP RST edge cases using deterministic loopback proxies without resorting to external network injectors or privileged OS tools.

## Key Differentiators
1. **Empirical Observation vs. Assumptions**: Instead of inferring cursor backpressure from client delays or exceptions, the lab inspects PostgreSQL catalog tables (`pg_cursors`, `pg_stat_activity`, `pg_locks`) and sequence progression.
2. **Deterministic Offline Execution**: Spawns an ephemeral PostgreSQL instance with strictly isolated process lifetimes, requiring zero root permissions, zero external network connectivity, and zero pre-configured database daemons.
3. **Loopback Transport Fault Injection**: Employs an unprivileged `LoopbackRelay` to trigger socket read timeouts and transport RST resets safely on local loopback.
