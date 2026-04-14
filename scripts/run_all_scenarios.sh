#!/usr/bin/env bash
# =============================================================================
# run_all_scenarios.sh
#
# Orchestrates a comprehensive suite of test scenarios for the WorkItem-based
# concurrency control mechanism.
#
# This script runs each scenario in sequence, capturing detailed results and
# presenting a summary comparison at the end.
#
# Pre-requisites:
#   • Service running with test-only router:
#       sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes"
#   • MongoDB running (WorkItems persist to: eacd-file-processor.workItems)
#   • jq recommended for formatted output
#   • Internet connectivity for timestamp retrieval (optional)
#
# Usage:
#   ./scripts/run_all_scenarios.sh [--base-url http://localhost:9867]
#
# =============================================================================
set -euo pipefail

BASE_URL="${1:-http://localhost:9867}"
SERVICE_PATH="/eacd-file-processor"
TEST_ONLY_PATH=""
RESULTS_DIR="./test-results"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RESULT_FILE="$RESULTS_DIR/scenarios-$TIMESTAMP.log"

# Create results directory
mkdir -p "$RESULTS_DIR"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_section() {
  local title="$1"
  echo
  echo "════════════════════════════════════════════════════════════════════"
  echo "  $title"
  echo "════════════════════════════════════════════════════════════════════"
  echo
}

log_result() {
  local status="$1"
  local message="$2"
  case "$status" in
    PASS) echo -e "${GREEN}✅  PASS${NC}  — $message" ;;
    WARN) echo -e "${YELLOW}⚠️   WARN${NC}  — $message" ;;
    FAIL) echo -e "${RED}❌  FAIL${NC}  — $message" ;;
    INFO) echo -e "${BLUE}ℹ️   INFO${NC}  — $message" ;;
  esac
}

# Test that service is reachable
health_check() {
  local url="$BASE_URL$SERVICE_PATH/admin/throttle/status"
  if curl -sf --connect-timeout 3 "$url" >/dev/null 2>&1; then
    return 0
  else
    return 1
  fi
}

echo "════════════════════════════════════════════════════════════════════"
echo "  WorkItem Concurrency Control — Comprehensive Scenario Testing"
echo "════════════════════════════════════════════════════════════════════"
echo
echo "Results will be saved to: $RESULT_FILE"
echo "Base URL: $BASE_URL"
echo
echo "Pre-flight checks..."

if ! health_check; then
  echo -e "${RED}ERROR${NC}: Cannot reach service at $BASE_URL$SERVICE_PATH"
  echo "  Is the service running with the test-only router?"
  echo "  sbt \"run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes\""
  exit 1
fi

log_result INFO "Service is reachable"

# Check for jq (optional but recommended)
if command -v jq >/dev/null 2>&1; then
  log_result INFO "jq found — formatted output enabled"
else
  log_result WARN "jq not found — output will be less formatted (install with: brew install jq)"
fi

# ── SCENARIO 1: Basic concurrency capping (small burst, default config) ──────
log_section "SCENARIO 1: Basic Concurrency Capping"
cat <<'EOF'
Purpose: Verify that concurrency is capped at max-concurrent

Test setup:
  • Create 5 WorkItems
  • Measure peak in-flight requests
  • Expected: all complete within max-concurrent limit

This is the simplest scenario — suitable for smoke testing that the
throttling mechanism is active.
EOF

echo
echo "Executing scenario 1..."
{
  scripts/simulate_enrolment_store_proxy_throttling.sh \
    --base-url "$BASE_URL" \
    --service-path "$SERVICE_PATH" \
    --count 5 \
    2>&1 | tee -a "$RESULT_FILE"
} || log_result WARN "Scenario 1 did not complete successfully"

# ── SCENARIO 2: Multiple sequential chunks ──────────────────────────────────
log_section "SCENARIO 2: Multiple Sequential Chunks"
cat <<'EOF'
Purpose: Observe chunk-by-chunk processing

Test setup:
  • Create 50 WorkItems (should produce ~12-13 chunks if max-concurrent=4)
  • Measure total elapsed time
  • Expected: Time = (50 / 4) * avg_response_time; peak = 4 throughout

This scenario demonstrates that the system processes WorkItems in
efficient batches, with concurrency steady at the configured limit.
EOF

echo
echo "Executing scenario 2..."
{
  scripts/simulate_enrolment_store_proxy_throttling.sh \
    --base-url "$BASE_URL" \
    --service-path "$SERVICE_PATH" \
    --count 50 \
    2>&1 | tee -a "$RESULT_FILE"
} || log_result WARN "Scenario 2 did not complete successfully"

# ── SCENARIO 3: Slow downstream simulation ───────────────────────────────────
log_section "SCENARIO 3: Slow Downstream (Stub Delay)"
cat <<'EOF'
Purpose: Demonstrate concurrency with artificial downstream latency

Test setup:
  • Create 20 WorkItems
  • Stub responds after 1000ms per request
  • Expected: peak in-flight = max-concurrent; total time ≈ (20 / max-concurrent) * 1000ms

This scenario shows that the concurrency limit is enforced even when
downstream responses are slow. Useful for understanding how the system
behaves under real-world latency conditions.
EOF

echo
echo "Executing scenario 3..."
{
  scripts/simulate_enrolment_store_proxy_throttling.sh \
    --base-url "$BASE_URL" \
    --service-path "$SERVICE_PATH" \
    --count 20 \
    --stub-delay-ms 1000 \
    2>&1 | tee -a "$RESULT_FILE"
} || log_result WARN "Scenario 3 did not complete successfully"

# ── SCENARIO 4: Concurrency saturation (overflow) ────────────────────────────
log_section "SCENARIO 4: Concurrency Saturation (Overflow)"
cat <<'EOF'
Purpose: Test the overflow scenario where requests exceed max-concurrent

Test setup:
  • Read max-concurrent from service
  • Create (max-concurrent + 5) WorkItems
  • Stub responds after 2000ms
  • Expected: availablePermits = 0; all overflow queued in MongoDB

This scenario directly tests the queue behavior. Overflow WorkItems
should remain in MongoDB with ProcessingStatus.Pending until concurrency
slots become available.
EOF

echo
echo "Executing scenario 4..."
{
  scripts/simulate_max_concurrency_timeout.sh \
    --base-url "$BASE_URL" \
    --service-path "$SERVICE_PATH" \
    --overflow 5 \
    --stub-delay-ms 2000 \
    2>&1 | tee -a "$RESULT_FILE"
} || log_result WARN "Scenario 4 did not complete successfully"

# ── SCENARIO 5: Extreme scale (many chunks) ──────────────────────────────────
log_section "SCENARIO 5: Extreme Scale (Many Chunks)"
cat <<'EOF'
Purpose: Test processing of a large number of WorkItems

Test setup:
  • Create 200 WorkItems
  • Use minimal stub delay (0ms)
  • Expected: time scales linearly with chunk count

This scenario verifies that the system remains stable under high load
and that MongoDB WorkItem queuing doesn't become a bottleneck.
EOF

echo
echo "Executing scenario 5..."
{
  scripts/simulate_enrolment_store_proxy_throttling.sh \
    --base-url "$BASE_URL" \
    --service-path "$SERVICE_PATH" \
    --count 200 \
    --status-sampling-ms 500 \
    2>&1 | tee -a "$RESULT_FILE"
} || log_result WARN "Scenario 5 did not complete successfully"

# ── SCENARIO 6: Very low max-concurrent (serial processing) ──────────────────
log_section "SCENARIO 6: Serial Processing (max-concurrent=1)"
cat <<'EOF'
Purpose: Verify behavior with max-concurrent=1

To run this scenario:
  1. Stop the current service
  2. Start with max-concurrent=1:
     sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes \
          -Dthrottle.enrolment-store-proxy.max-concurrent=1"
  3. Run this command:
     ./scripts/simulate_enrolment_store_proxy_throttling.sh \\
       --base-url "$BASE_URL" \\
       --service-path "$SERVICE_PATH" \\
       --count 10

Expected: peak in-flight = 1; total time ≈ 10 * avg_response_time
          (purely serial processing, no concurrency)
EOF

echo

# ── Summary ─────────────────────────────────────────────────────────────────
log_section "SUMMARY"

cat <<'EOF'
All core scenarios have been executed.

Next steps:

1. Review results:
   Less verbose:   tail -50 "$RESULT_FILE"
   Full details:   cat "$RESULT_FILE"

2. Analyze patterns:
   • Did peak concurrency match max-concurrent?
   • Did total time scale linearly with chunk count?
   • Did overflow requests queue correctly?

3. Test custom configurations:
   Edit conf/application.conf to change max-concurrent and restart:
     throttle {
       enrolment-store-proxy {
         max-concurrent = 2     # try 1, 2, 4, 8, etc.
       }
     }

4. Run individual scenarios:
   Basic:        ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 10
   Slow stub:    ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 20 --stub-delay-ms 2000
   Saturation:   ./scripts/simulate_max_concurrency_timeout.sh --overflow 3

5. Monitor MongoDB WorkItems:
   db.workItems.find({}, {batchId: 1, processingStatus: 1, item: 1}).pretty()
   db.workItems.countDocuments({processingStatus: "Pending"})
   db.workItems.countDocuments({processingStatus: "InProgress"})
   db.workItems.countDocuments({processingStatus: "Succeeded"})

6. View service logs:
   tail -f logs/eacd-file-processor.log

7. Read documentation:
   cat docs/THROTTLING.md
EOF

echo
log_result INFO "All scenarios completed. Results saved to: $RESULT_FILE"

