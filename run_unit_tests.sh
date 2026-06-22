#!/bin/bash
cd "$(dirname "$0")"
echo "Running unit tests with isolated in-memory H2 database..."
echo "=================================================="
mvn clean test 2>&1
EXIT_CODE=$?
echo "=================================================="
echo "Exit code: $EXIT_CODE"
exit $EXIT_CODE
