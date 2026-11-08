#!/bin/sh
set -eu

SCRIPT_DIR=$(cd "$(dirname "$0")/.." && pwd)
MAVEN_REPO="${SCRIPT_DIR}/build/mavenrepo"

echo "==> Running Maven compilation and test suite..."
if [ -d "$MAVEN_REPO" ]; then
    mvn -Dmaven.repo.local="$MAVEN_REPO" -o clean test package
else
    mvn clean test package
fi

echo "==> Verifying packaged standalone JAR with CLI assertions..."
JAR_PATH="${SCRIPT_DIR}/target/java-jdbc-cursor-rehearsal-0.1.0-standalone.jar"
java -jar "$JAR_PATH" --assert

echo "==> Verifying JSON output mode..."
java -jar "$JAR_PATH" --json > /dev/null

echo "==> All offline JDBC cursor rehearsal tests passed successfully!"
