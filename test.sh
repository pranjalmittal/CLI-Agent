#!/usr/bin/env bash
set -e

# Compile and run test suite (pure Java, zero dependencies needed)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

mkdir -p target/classes target/test-classes
javac -d target/classes $(find src/main/java -name "*.java")
javac -cp target/classes -d target/test-classes $(find src/test/java -name "*.java")
java -cp target/classes:target/test-classes agent.AgentTest
