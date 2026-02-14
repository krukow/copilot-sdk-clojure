#!/bin/bash
# Run all examples using -X invocation

set -e

echo "=== basic-chat ==="
clojure -A:examples -X basic-chat/run

echo ""
echo "=== helpers-query ==="
clojure -A:examples -X helpers-query/run

echo ""
echo "=== helpers-query/run-multi ==="
clojure -A:examples -X helpers-query/run-multi

echo ""
echo "=== tool-integration ==="
clojure -A:examples -X tool-integration/run

echo ""
echo "=== multi-agent ==="
clojure -A:examples -X multi-agent/run

echo ""
echo "=== config-skill-output ==="
clojure -A:examples -X config-skill-output/run

echo ""
echo "=== metadata-api ==="
clojure -A:examples -X metadata-api/run

echo ""
echo "=== permission-bash ==="
clojure -A:examples -X permission-bash/run

echo ""
echo "=== session-events ==="
clojure -A:examples -X session-events/run

echo ""
echo "=== user-input ==="
clojure -A:examples -X user-input/run-simple
