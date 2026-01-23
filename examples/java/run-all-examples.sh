#!/bin/bash
# Run all Java examples

set -e
cd "$(dirname "$0")"

echo "Building..."
mvn compile -q

echo ""
echo "=========================================="
echo "Running Java Examples"
echo "=========================================="

run_example() {
    local profile=$1
    local desc=$2
    echo ""
    echo "--- $desc ---"
    mvn exec:java ${profile:+-P$profile} -q
}

run_example "" "Basic Query"
run_example "streaming" "Streaming Output"
run_example "conversation" "Multi-turn Conversation"
run_example "async" "Async API"
run_example "tools" "Custom Tools"
run_example "permission" "Permission Handling"
run_example "multi-agent" "Multi-Agent Collaboration"
run_example "parallel" "Parallel Queries"
run_example "events" "Event Handling"

echo ""
echo "=========================================="
echo "All examples completed!"
echo "=========================================="
