#!/usr/bin/env bash
set -e

# Compile and run WinCliAgent (pure Java, zero dependencies needed)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

mkdir -p target/classes
javac -d target/classes $(find src/main/java -name "*.java")
java -cp target/classes agent.Main "$@"
