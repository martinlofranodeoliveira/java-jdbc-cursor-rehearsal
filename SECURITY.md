# Security Policy

## Supported Versions

Only the latest release of `java-jdbc-cursor-rehearsal` receives security updates.

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability in this project, please report it privately:

1. **GitHub Security Advisory**: Open a private advisory report via [GitHub Security Advisories](https://github.com/martinlofranodeoliveira/java-jdbc-cursor-rehearsal/security/advisories/new).
2. **Email**: Contact security@lofranotecnologia.com.br with technical details and reproduction steps.

### Security Guarantees & Bounds
- **Local Fixture Isolation**: Operates exclusively against an unprivileged, ephemeral PostgreSQL 17 cluster fixture bound to `127.0.0.1` inside a network-none execution namespace.
- **Trust Authentication Only**: No passwords, tokens, or external network connections are used.
- **No Subprocess Discovery**: Process tracking uses strict direct-child process ownership with explicit PID lifecycle management. PostgreSQL internal backend PIDs (`pg_backend_pid()`) are strictly isolated to SQL observation and never signaled or targeted with OS-level `kill`.
- **Bounded Resource Usage**: JVM max heap is strictly capped (`-Xmx128m`), PostgreSQL `shared_buffers` is limited to 16MB, `work_mem` to 1MB, and active connections bounded to 16.
- **No Command Injection**: All SQL parameterization utilizes typed JDBC prepared statements; no string concatenation is used for identifiers or query criteria.
