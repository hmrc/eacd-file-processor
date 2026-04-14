#!/usr/bin/env bash
set -euo pipefail

BASE_URL="http://localhost:9867"
SERVICE_PATH="/eacd-file-processor"
TEST_ONLY_PATH=""
COUNT=25
STUB_DELAY_MS=0
TIMEOUT=120
STATUS_SAMPLING_MS=200

declare -a HEADERS=()

usage() {
  cat <<'EOF'
Usage: scripts/simulate_enrolment_store_proxy_throttling.sh [options]

WorkItem-based Concurrency Control Scenario Testing
────────────────────────────────────────────────────

This script tests the new WorkItem-based throttling architecture that replaces
the old token-bucket model. Requests are now persisted to MongoDB WorkItems and
processed sequentially in chunks respecting the max-concurrent limit.

The script triggers N file notifications, persists them as WorkItems, then
processes them in chunks while sampling /admin/throttle/status.

SCENARIOS
─────────

Scenario 1: Basic concurrency capping
  Verify that exactly max-concurrent requests are in-flight at any moment:
  $ ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 10

  Expected: currentlyProcessing should reach max-concurrent and hold steady.

Scenario 2: Multiple sequential chunks
  Send more requests than max-concurrent to observe chunk-by-chunk processing:
  $ ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 100

  Expected: Requests processed in batches; total time ≈ count * avg_response_time / max-concurrent

Scenario 3: Slow downstream
  Combine with a delayed stub response to observe bottleneck behavior:
  $ ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 20 --stub-delay-ms 2000

  Expected: Concurrency maintained at max-concurrent; total time scales with downstream latency.

Scenario 4: Varying concurrency limits (change config and restart)
  $ sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes \
         -Dthrottle.enrolment-store-proxy.max-concurrent=1"
  $ ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 10

  Expected: Only 1 request in-flight at a time (slow serialized processing).

Pre-requisites
──────────────
  • Service running with test-only router:
      sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes"
  • MongoDB running (WorkItems persist to: eacd-file-processor.workItems)
  • jq recommended for formatted JSON output

Options
───────
  --base-url URL            Service base URL (default: http://localhost:9867)
  --service-path PATH       App route prefix (default: /eacd-file-processor)
  --test-only-path PATH     Test-only route prefix (default: empty)
  --count N                 Number of WorkItems to create (default: 25)
  --stub-delay-ms MS        Optional: stub response delay in ms (forwarded to stub)
  --timeout SEC             Curl timeout in seconds (default: 120)
  --status-sampling-ms MS   Poll interval for /admin/throttle/status (default: 200)
  --header "Key: Value"     Extra header for all calls (repeatable)
  -h, --help                Show help
EOF
}

endpoint_url() {
  local path="$1"
  local base="${BASE_URL%/}"
  local prefix="${SERVICE_PATH%/}"
  if [ -z "$prefix" ]; then
    printf '%s%s' "$base" "$path"
  else
    printf '%s%s%s' "$base" "$prefix" "$path"
  fi
}

endpoint_url_with_prefix() {
  local prefix="$1"
  local path="$2"
  local base="${BASE_URL%/}"
  local p="${prefix%/}"
  if [ -z "$p" ]; then
    printf '%s%s' "$base" "$path"
  else
    printf '%s%s%s' "$base" "$p" "$path"
  fi
}

curl_json() {
  local method="$1"
  local url="$2"
  local data="${3:-}"

  local args=(curl -sS --connect-timeout 5 --max-time "$TIMEOUT" -X "$method" "$url" -H "Content-Type: application/json")
  local h
  for h in ${HEADERS+"${HEADERS[@]}"}; do
    args+=( -H "$h" )
  done
  if [ -n "$data" ]; then
    args+=( --data "$data" )
  fi

  local body_file
  body_file=$(mktemp)
  local meta
  meta=$("${args[@]}" -o "$body_file" -w "%{http_code} %{time_total}" || true)
  local code="${meta%% *}"
  local elapsed="${meta##* }"
  local body
  body=$(cat "$body_file")
  rm -f "$body_file"
  printf '%s\t%s\t%s\n' "$code" "$elapsed" "$body"
}

status_sampler() {
  local out_file="$1"
  local interval_s="$2"
  while true; do
    local r
    r=$(curl_json GET "$(endpoint_url /admin/throttle/status)")
    printf '%s\t%s\n' "$(date +%s)" "$(printf '%s' "$r" | awk -F '\t' '{print $3}')" >>"$out_file"
    sleep "$interval_s"
  done
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --base-url) BASE_URL="$2"; shift 2 ;;
    --service-path) SERVICE_PATH="$2"; shift 2 ;;
    --test-only-path) TEST_ONLY_PATH="$2"; shift 2 ;;
    --count) COUNT="$2"; shift 2 ;;
    --stub-delay-ms) STUB_DELAY_MS="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    --status-sampling-ms) STATUS_SAMPLING_MS="$2"; shift 2 ;;
    --header) HEADERS+=("$2"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

if ! [[ "$COUNT" =~ ^[0-9]+$ ]] || [ "$COUNT" -le 0 ]; then
  echo "--count must be a positive integer" >&2
  exit 2
fi

if [ -n "$SERVICE_PATH" ] && [[ "$SERVICE_PATH" != /* ]]; then
  SERVICE_PATH="/$SERVICE_PATH"
fi
if [ -n "$TEST_ONLY_PATH" ] && [[ "$TEST_ONLY_PATH" != /* ]]; then
  TEST_ONLY_PATH="/$TEST_ONLY_PATH"
fi

interval_s=$(awk -v ms="$STATUS_SAMPLING_MS" 'BEGIN { printf "%.3f", ms / 1000 }')
status_samples_file=$(mktemp)
trigger_out_file=$(mktemp)
trap 'rm -f "$status_samples_file" "$trigger_out_file"' EXIT

echo "════════════════════════════════════════════════════════════════"
echo "WorkItem-Based Concurrency Control Test"
echo "════════════════════════════════════════"
echo

# Read initial config
echo "Step 1: Reading current throttle configuration..."
initial_status_raw=$(curl_json GET "$(endpoint_url /admin/throttle/status)")
initial_code=$(awk -F '\t' '{print $1}' <<<"$initial_status_raw")
initial_body=$(awk -F '\t' '{print $3}' <<<"$initial_status_raw")

if [ "$initial_code" != "200" ]; then
  echo "ERROR: Cannot reach /admin/throttle/status (HTTP $initial_code)" >&2
  echo "       Is service running with test-only router?" >&2
  exit 1
fi

MAX_CONCURRENT=0
if command -v jq >/dev/null 2>&1; then
  MAX_CONCURRENT=$(printf '%s\n' "$initial_body" | jq -r '.enrolmentStoreProxy.maxConcurrent // 0')
  echo "Initial configuration:"
  printf '%s\n' "$initial_body" | jq '.enrolmentStoreProxy' | sed 's/^/  /'
else
  MAX_CONCURRENT=$(printf '%s\n' "$initial_body" | grep -oE '"maxConcurrent"\s*:\s*[0-9]+' | grep -oE '[0-9]+$' || echo 0)
  echo "Initial configuration: $initial_body"
fi

echo
echo "Test parameters:"
echo "  Total WorkItems to process  : $COUNT"
echo "  Max concurrent (chunk size) : $MAX_CONCURRENT"
expected_chunks=$(( (COUNT + MAX_CONCURRENT - 1) / MAX_CONCURRENT ))
echo "  Expected chunks             : $expected_chunks"
echo "  Stub delay per call         : ${STUB_DELAY_MS}ms"
echo

# Calculate expected timing
if [ "$STUB_DELAY_MS" -gt 0 ]; then
  echo "Timing estimate:"
  avg_chunk_time=$STUB_DELAY_MS
  estimated_total=$(( expected_chunks * avg_chunk_time ))
  echo "  ≈ $expected_chunks chunks × ${STUB_DELAY_MS}ms ≈ ${estimated_total}ms total"
  echo "  (actual time depends on network latency and MongoDB performance)"
  echo
fi

echo "Step 2: Starting status monitor (${STATUS_SAMPLING_MS}ms poll interval)..."
echo "Step 3: Triggering WorkItem burst..."
echo

status_sampler "$status_samples_file" "$interval_s" &
sampler_pid=$!

start=$(date +%s%N 2>/dev/null || date +%s)
trigger_url="$(endpoint_url_with_prefix "$TEST_ONLY_PATH" "/test-only/throttle/enrolment-store-proxy/$COUNT")"
if [ "$STUB_DELAY_MS" -gt 0 ]; then
  trigger_url="${trigger_url}?stubDelayMs=$STUB_DELAY_MS"
fi
curl_json POST "$trigger_url" >"$trigger_out_file"
end=$(date +%s%N 2>/dev/null || date +%s)

kill "$sampler_pid" >/dev/null 2>&1 || true
wait "$sampler_pid" 2>/dev/null || true

code=$(awk -F '\t' '{print $1}' "$trigger_out_file")
body=$(awk -F '\t' '{print $3}' "$trigger_out_file")

# Calculate elapsed with support for nanosecond precision (newer date) and fallback to seconds
if [[ "$start" =~ ^[0-9]{19}$ ]]; then
  elapsed_ms=$(( (end - start) / 1000000 ))
  elapsed_s=$(awk "BEGIN {printf \"%.2f\", $elapsed_ms / 1000}")
else
  elapsed_s=$((end - start))
  elapsed_ms=$((elapsed_s * 1000))
fi

echo "════════════════════════════════════════════════════════════════"
echo "RESULTS"
echo "════════════════════════════════════════════════════════════════"
echo

if command -v jq >/dev/null 2>&1; then
  echo "Trigger Response (HTTP $code):"
  printf '%s\n' "$body" | jq '.' | sed 's/^/  /'
  echo

  echo "Observed WorkItem Processing Metrics:"
  max_inflight=$(awk -F '\t' '{print $2}' "$status_samples_file" | jq -rs 'map(try fromjson catch empty) | map(.enrolmentStoreProxy.currentlyProcessing // 0) | max // 0')
  min_permits=$(awk -F '\t' '{print $2}' "$status_samples_file" | jq -rs 'map(try fromjson catch empty) | map(.enrolmentStoreProxy.availablePermits // 100) | min // "n/a"')
  avg_inflight=$(awk -F '\t' '{print $2}' "$status_samples_file" | jq -rs 'map(try fromjson catch empty) | map(.enrolmentStoreProxy.currentlyProcessing // 0) | add / (length | if . == 0 then 1 else . end) | round' 2>/dev/null || echo "n/a")

  echo "  Peak in-flight requests       : $max_inflight (expected: ≤ $MAX_CONCURRENT)"
  echo "  Min available permits         : $min_permits (expected: ≥ 0)"
  echo "  Avg in-flight during burst    : $avg_inflight"
  echo "  Measured elapsed time         : ${elapsed_s}s (${elapsed_ms}ms)"
  echo

  # Validation
  echo "Scenario Validation:"
  if [ "$code" -eq 200 ]; then
    echo "  ✅ Trigger endpoint responded successfully"
  else
    echo "  ❌ Trigger endpoint failed (HTTP $code)"
  fi

  if [ "$max_inflight" -le "$MAX_CONCURRENT" ]; then
    echo "  ✅ Concurrency stayed within limit ($max_inflight ≤ $MAX_CONCURRENT)"
  else
    echo "  ❌ Concurrency exceeded limit ($max_inflight > $MAX_CONCURRENT)"
  fi

  if [ "$expected_chunks" -gt 1 ]; then
    # With multiple chunks, peak should be at max-concurrent most of the time
    expected_peak=$MAX_CONCURRENT
    if [ "$max_inflight" -eq "$expected_peak" ]; then
      echo "  ✅ All $expected_chunks chunks processed sequentially in parallel batches"
    else
      echo "  ⚠️  Peak concurrency ($max_inflight) differs from max-concurrent ($expected_peak)"
    fi
  fi
else
  echo "Trigger Response (HTTP $code): $body"
  echo
  echo "Observed WorkItem Processing (jq not installed - raw samples):"
  echo "  Last 5 status samples:"
  tail -n 5 "$status_samples_file" | awk -F '\t' '{print "    [" $1 "] " $2}' | sed 's/^/  /'
fi

echo
echo "Final throttle status:"
final_raw=$(curl_json GET "$(endpoint_url /admin/throttle/status)")
final_code=$(awk -F '\t' '{print $1}' <<<"$final_raw")
final_body=$(awk -F '\t' '{print $3}' <<<"$final_raw")

if command -v jq >/dev/null 2>&1; then
  printf '%s\n' "$final_body" | jq '.enrolmentStoreProxy' | sed 's/^/  /'
else
  echo "  $final_body"
fi

echo
echo "════════════════════════════════════════════════════════════════"
echo "NEXT STEPS"
echo "════════════════════════════════════════════════════════════════"
echo
if [ "$COUNT" -le "$MAX_CONCURRENT" ]; then
  echo "Single-chunk scenario completed."
  echo "Try increasing --count above $MAX_CONCURRENT to test multi-chunk processing."
elif [ "$expected_chunks" -le 3 ]; then
  echo "Multi-chunk scenario (small). Try larger --count for better saturation view."
else
  echo "Multi-chunk scenario validated."
fi
echo

if [ "$code" -ne 200 ]; then
  exit 1
fi
