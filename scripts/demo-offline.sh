#!/bin/sh
set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")/.." && pwd)
JAR_PATH="${SCRIPT_DIR}/target/java-jdbc-cursor-rehearsal-0.1.0-standalone.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "==> Standalone JAR not found; building offline..."
    mvn -Dmaven.repo.local="${SCRIPT_DIR}/build/mavenrepo" -o package -DskipTests
fi

MODE="${1:-}"

if [ "$MODE" = "--assert" ]; then
    exec java -jar "$JAR_PATH" --assert
elif [ "$MODE" = "--json" ]; then
    exec java -jar "$JAR_PATH" --json
else
    echo "==> Running JDBC Cursor Rehearsal Demo (Standard Formatted Output)..."
    java -jar "$JAR_PATH" --assert
    echo ""
    echo "==> Running JDBC Cursor Rehearsal Demo (JSON Output)..."
    java -jar "$JAR_PATH" --json
    echo ""
    echo "==> Demo completed successfully."
fi
