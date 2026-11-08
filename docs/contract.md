# Protocol Contract: JDBC Cursor & Cancellation Rehearsal

## Execution Guarantees

### 1. PostgreSQL Fixture Lifecycle
- The test harness launches an ephemeral PostgreSQL instance via `initdb -U fixture -A trust` on an unreserved loopback TCP port.
- Process termination uses standard SIGTERM destruction followed by recursive deletion of the temporary data cluster.
- All connections are restricted to `127.0.0.1` and require no authentication credentials.

### 2. Server-Side Cursor Contract
- Server-side portals are created **only** when all three conditions are met simultaneously:
  1. `connection.setAutoCommit(false)`
  2. `statement.setFetchSize(N)` where `N > 0`
  3. `resultSetType == ResultSet.TYPE_FORWARD_ONLY`
- Violation of any of these conditions causes the PostgreSQL JDBC driver to pull all result rows into client JVM memory upfront, creating 0 server portals in `pg_cursors`.

### 3. Session Locality Contract
- Portals registered in `pg_cursors` are session-private. An independent observer session querying `SELECT * FROM pg_cursors` will see 0 portals for another session's cursor, even while that cursor is actively being fetched.
- Cross-session backend inspection is performed via `pg_stat_activity` and `pg_locks` using the client's `pg_backend_pid()` and unique `ApplicationName`.

### 4. Cancellation Contract
- **Initial Batch**: When the initial `executeQuery()` is blocked on a row barrier, `Statement.cancel()` successfully issues a cancellation packet and unblocks the executing thread with SQLState `57014` (`ERRCODE_QUERY_CANCELED`).
- **Subsequent Batches**: Once the initial batch is delivered to the client, the pgJDBC driver transitions its internal `statementState` to `IDLE`. A call to `Statement.cancel()` during a subsequent `r.next()` network fetch is a no-op. Unblocking requires explicitly invoking `connection.unwrap(PGConnection.class).cancelQuery()`.
- **Session Recovery**: After cancellation, issuing `connection.rollback()` cleanses the transaction block, collapses the server portal, and enables subsequent sentinel queries (`SELECT 4242`) to succeed on the same connection.

### 5. Transport Fault Contract
- **Socket Timeout**: When network traffic is withheld on the server side, client-side reads trigger a `SocketTimeoutException` wrapped as a `SQLException`. The connection is permanently poisoned and must be discarded.
- **TCP RST**: Abrupt socket closure (`SO_LINGER=0`) triggers an immediate transport error. Stale rows or pending buffers are rejected and the connection is closed.
