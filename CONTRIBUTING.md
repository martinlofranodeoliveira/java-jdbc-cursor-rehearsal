# Contributing to JDBC Cursor and Cancellation Rehearsal

Thank you for contributing to `java-jdbc-cursor-rehearsal`.

## Guiding Principles
1. **Zero Unvetted Dependencies**: All runtime and build dependencies are strictly pinned in `pom.xml` and must compile offline.
2. **Deterministic Offline Execution**: All scenarios must run deterministically with `--network none` in containerized environments.
3. **Protocol Grounding**: Assertions must observe actual server-side portals (`pg_cursors`), lock barriers (`pg_locks`), and session activity (`pg_stat_activity`) rather than inferring behavior from client-side exceptions or arbitrary sleep delays.
4. **Clean Code & Documentation**: Ensure full parity between English and Portuguese documentation.

## Development Workflow
```bash
# Compile and run test suite offline
/bin/sh scripts/test-offline.sh

# Run asserting demo
/bin/sh scripts/demo-offline.sh --assert

# Run demo with JSON report
/bin/sh scripts/demo-offline.sh --json
```
