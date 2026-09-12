#!/bin/bash

# Health check simulator - polls K8s probes asynchronously every second
# Based on Helm chart verne-statefulset probe configuration:
# - Liveness: /actuator/health/liveness (10s period, 30 failure threshold)
# - Readiness: /actuator/health/readiness (5s period, 3 failure threshold)
# - Startup: /actuator/health/liveness (10s period, 30 failure threshold)

BASE_URL="http://localhost:8085/actuator/health"
INTERVAL=1
TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

echo "Starting K8s health probe simulator (async)..."
echo "Polling probes every $INTERVAL second(s) - all checks run in parallel"
echo "Press Ctrl+C to stop"
echo ""

count=0
while true; do
  ((count++))
  timestamp=$(date '+%Y-%m-%d %H:%M:%S.%3N')

  # Spawn all three curl requests in background (async)
  curl -s -w "\n%{http_code}" "$BASE_URL" > "$TMPDIR/health.out" 2>&1 &
  health_pid=$!

  curl -s -w "\n%{http_code}" "$BASE_URL/liveness" > "$TMPDIR/liveness.out" 2>&1 &
  liveness_pid=$!

  curl -s -w "\n%{http_code}" "$BASE_URL/readiness" > "$TMPDIR/readiness.out" 2>&1 &
  readiness_pid=$!

  # Wait for all three to complete
  wait $health_pid $liveness_pid $readiness_pid

  # Parse responses
  health=$(cat "$TMPDIR/health.out")
  health_code=$(echo "$health" | tail -n1)
  health_body=$(echo "$health" | head -n-1)
  health_status=$(echo "$health_body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4 || echo "UNKNOWN")

  liveness=$(cat "$TMPDIR/liveness.out")
  liveness_code=$(echo "$liveness" | tail -n1)
  liveness_body=$(echo "$liveness" | head -n-1)
  liveness_status=$(echo "$liveness_body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4 || echo "UNKNOWN")

  readiness=$(cat "$TMPDIR/readiness.out")
  readiness_code=$(echo "$readiness" | tail -n1)
  readiness_body=$(echo "$readiness" | head -n-1)
  readiness_status=$(echo "$readiness_body" | grep -o '"status":"[^"]*"' | cut -d'"' -f4 || echo "UNKNOWN")

  # Color codes for output
  GREEN='\033[0;32m'
  RED='\033[0;31m'
  NC='\033[0m' # No Color

  # Format status with color
  format_status() {
    local code=$1
    local status=$2
    if [ "$code" = "200" ] && [ "$status" = "UP" ]; then
      echo -e "${GREEN}✓ UP${NC}"
    else
      echo -e "${RED}✗ DOWN${NC}"
    fi
  }

  # Output in Helm chart probe format
  echo "[$count] $timestamp"
  echo "  Health:    HTTP $health_code | $(format_status "$health_code" "$health_status")"
  echo "  Liveness:  HTTP $liveness_code | $(format_status "$liveness_code" "$liveness_status") (period: 10s, failure-threshold: 30)"
  echo "  Readiness: HTTP $readiness_code | $(format_status "$readiness_code" "$readiness_status") (period: 5s, failure-threshold: 3)"
  echo ""

  sleep $INTERVAL
done