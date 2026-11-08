# Architectural Design: JDBC Cursor & Cancellation Rehearsal

## Architecture Overview

```
+---------------------------------------------------------------------------------+
|                               CursorRehearsalLab                                |
|                                                                                 |
|  +--------------------+    +--------------------+    +-----------------------+  |
|  |   Client Session   |    |  Observer Session  |    |  Lock Holder Session  |  |
|  |  (cli_s1..cli_s8)  |    | (obs_s1..obs_s8)   |    |    (lock_holder)      |  |
|  +---------+----------+    +---------+----------+    +-----------+-----------+  |
+------------|-------------------------|---------------------------|--------------+
             |                         |                           |
             | (optional)              |                           |
             v                         |                           |
    +-----------------+                |                           |
    |  LoopbackRelay  |                |                           |
    | (Fault Injector)|                |                           |
    +--------+--------+                |                           |
             |                         |                           |
             +-------------------------v---------------------------+
                                       |
                                       v
                    +------------------------------------+
                    |   Ephemeral PostgreSQL Instance   |
                    |            (Port: P)               |
                    |                                    |
                    |  - pg_cursors (session portal)     |
                    |  - pg_stat_activity (backend pid)  |
                    |  - pg_locks (advisory barrier)     |
                    |  - progress (eval sequence)        |
                    +------------------------------------+
```

## Core Components

### 1. PostgresClusterManager
- Orchestrates the creation of temporary database clusters on Linux loopback (`127.0.0.1`).
- Generates a dedicated temporary directory structure containing `data/` and `socket/`.
- Executes `initdb` with UTF-8 encoding and trusted authentication.
- Configures `postgres` with lean resource caps (`shared_buffers=16MB`, `work_mem=1MB`, `max_connections=16`, `autovacuum=off`, `jit=off`, `fsync=off`).
- Installs the PL/pgSQL barrier helper `fixture_touch(row_val, lock_at)` which increments a `progress` sequence (`CACHE 1`) and conditionally waits on `pg_advisory_lock()`.

### 2. LoopbackRelay
- Operates as a transparent TCP proxy on `127.0.0.1` between the JDBC client and PostgreSQL.
- Implements two fault modes:
  - `setWithholdResponses(true)`: Reads server traffic into memory without relaying it to the client, triggering client-side `socketTimeout`.
  - `resetAllActiveSockets()`: Sets `SO_LINGER` to `(true, 0)` and forcefully closes open sockets, emitting a TCP RST frame to simulate sudden network disruption.

### 3. CursorRehearsalLab
- Executes 8 distinct scenario workflows verifying portal lifecycle, demand accounting, query timeouts, statement cancellation, driver state transitions, and connection poisoning.
- Each scenario produces a typed `ScenarioResult` record containing execution status and evidence details.
