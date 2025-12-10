#!/bin/bash
# Karate Debug Server Launcher
# This script starts the Karate debug server with the correct classpath order
# to ensure ZenWave's vscode.jar is loaded first (required for open-source debugging)

set -e

# Parse arguments
VSCODE_PORT="${1:-0}"
FEATURE_PATH="${2:-}"
KARATE_ENV="${3:-dev}"
WORKSPACE_ROOT="${4:-$(pwd)}"

if [ "$VSCODE_PORT" = "0" ] || [ -z "$VSCODE_PORT" ]; then
    echo "Usage: karate-debug.sh <port> [feature-path] [karate-env] [workspace-root]"
    exit 1
fi

# Find Java
if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v /usr/libexec/java_home &> /dev/null; then
    JAVA="$(/usr/libexec/java_home)/bin/java"
else
    JAVA="java"
fi

# Find ZenWave's vscode.jar
VSCODE_JAR=""
for dir in ~/.vscode/extensions/karateide.karate-ide-*/resources; do
    if [ -f "$dir/vscode.jar" ]; then
        VSCODE_JAR="$dir/vscode.jar"
        break
    fi
done

if [ -z "$VSCODE_JAR" ] || [ ! -f "$VSCODE_JAR" ]; then
    echo "ERROR: Could not find ZenWave vscode.jar"
    echo "Please install the karateide.karate-ide extension from VS Code marketplace"
    exit 1
fi

cd "$WORKSPACE_ROOT"

# Build classpath using Maven
CLASSPATH_FILE="$WORKSPACE_ROOT/target/karate-debug-cp.txt"
mvn -q dependency:build-classpath -Dmdep.outputFile="$CLASSPATH_FILE"

if [ ! -f "$CLASSPATH_FILE" ]; then
    echo "ERROR: Failed to build classpath"
    exit 1
fi

MAVEN_CLASSPATH=$(cat "$CLASSPATH_FILE")

# Build full classpath - vscode.jar MUST be first!
CLASSPATH="$VSCODE_JAR"
CLASSPATH="$CLASSPATH:$WORKSPACE_ROOT/target/test-classes"
CLASSPATH="$CLASSPATH:$WORKSPACE_ROOT/src/test/java"
CLASSPATH="$CLASSPATH:$MAVEN_CLASSPATH"

echo "Starting Karate debug server..."
echo "  Port: $VSCODE_PORT"
echo "  Environment: $KARATE_ENV"
echo "  Java: $JAVA"
echo "  vscode.jar: $VSCODE_JAR"

# Start the debug server
exec "$JAVA" \
    "-Dkarate.env=$KARATE_ENV" \
    "-Dvscode.port=$VSCODE_PORT" \
    -cp "$CLASSPATH" \
    com.intuit.karate.Main \
    --backup-reportdir=false \
    --debug-keepalive \
    -d

