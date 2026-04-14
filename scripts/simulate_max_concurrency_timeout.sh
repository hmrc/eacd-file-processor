#!/usr/bin/env bash
# =============================================================================
# simulate_max_concurrency_timeout.sh
#
# Tests WorkItem-based concurrency saturation scenario.
#
# Demonstrates that the WorkItem throttling mechanism properly respects the
# max-concurrent limit by:
#  1. Creating more WorkItems than max-concurrent
#  2. Observing that only max-concurrent requests are in-flight at a time
#  3. Confirming overflow requests queue and process sequentially
#  4. Showing how slow downstream responses extend total processing time
#
# What the script does
# ────────────────────
#  1. Reads the configured max-concurrent value from /admin/throttle/status.
#  2. Persists (max-concurrent + OVERFLOW) WorkItems to MongoDB.
#  3. Processing begins immediately: first max-concurrent items are processed
#     in parallel; when any completes, the next item in the queue starts.
#  4. The stub endpoint is configured with --stub-delay-ms to simulate slow
#     downstream responses.
#  5. Meanwhile, /admin/throttle/status is polled every STATUS_SAMPLING_MS so
#     you can observe:
#       • currentlyProcessing  → holds at maxConcurrent while items remain
#       • availablePermits     → drops to 0 during processing (concurrency cap)
#       • chunk transitions    → visible as drops in currentlyProcessing
#  6. Results show peak concurrency, total elapsed time, and chunk boundaries.
#
# Pre-requisites
# ──────────────
#  • Service running with test-only router:
#      sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes"
#  • MongoDB running (WorkItems stored in: eacd-file-processor.workItems)
#  • The stub accepts a ?delayMs= query parameter for artificial delays.
#  • jq recommended for structured output (falls back to raw JSON if absent).
#
# Usage example
# ─────────────
#  # Test concurrency saturation with slow downstream
#  scripts/simulate_max_concurrency_timeout.sh \
#      --overflow 3 \
#      --stub-delay-ms 2000 \
#      --status-sampling-ms 300
# =============================================================================
set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
BASE_URL="http://localhost:9867"
SERVICE_PATH="/eacd-file-processor"
TEST_ONLY_PATH=""
OVERFLOW=3                  # extra WorkItems beyond max-concurrent
STUB_DELAY_MS=2000          # ms each stub response takes
STATUS_SAMPLING_MS=300      # poll interval for /admin/throttle/status
TRIGGER_TIMEOUT=120         # curl max-time for the trigger POST itself

declare -a HEADERS=()

# ── Helpers ───────────────────────────────────────────────────────────────────
usage() {
  cat <<'EOF'
Usage: scripts/simulate_max_concurrency_timeout.sh [options]

WorkItem Concurrency Saturation Scenario
─────────────────────────────────────────

This script tests the concurrency ceiling by creating more WorkItems than the
max-concurrent limit allows, forcing some to queue while others execute.

Key observations:
  • currentlyProcessing will reach maxConcurrent and hold steady
  • availablePermits will drop to 0 (all slots occupied)
  • Overflow WorkItems queue in MongoDB until a slot becomes available
  • Total processing time = ceil(total / maxConcurrent) × average_response_time

Scenario: Test with slow downstream responses
  The stub is configured with --stub-delay-ms to simulate a slow downstream
  service. Each WorkItem will block for this duration, demonstrating how
  concurrency slots are held during the entire request lifetime.

Pre-requisites
  • Service running with test-only router:
      sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes"
  • MongoDB running (WorkItems stored in: eacd-file-processor.workItems)

Options
───────
  --base-url URL            Service base URL (default: http://localhost:9867)
  --service-path PATH       App route prefix (default: /eacd-file-processor)
  --test-only-path PATH     Test-only route prefix (default: empty)
  --overflow N              Extra WorkItems beyond max-concurrent (default: 3)
                            Total = maxConcurrent + overflow
  --stub-delay-ms MS        How long the stub takes to respond (default: 2000)
                            Simulates slow downstream service latency
  --status-sampling-ms MS   Status poll interval in milliseconds (default: 300)
  --header "Key: Value"     Extra header for all calls (repeatable)
  -h, --help                Show this help text
EOF
}

endpoint_url() {
  local path="$1"
  printf '%s%s%s' "${BASE_URL%/}" "${SERVICE_PATH%/}" "$path"
}

endpoint_url_with_prefix() {
  local prefix="$1"
  local path="$2"
  local p="${prefix%/}"
  if [ -z "$p" ]; then
    printf '%s%s' "${BASE_URL%/}" "$path"
  else
    printf '%s%s%s' "${BASE_URL%/}" "$p" "$path"
  fi
}

curl_json() {
  local method="$1"
  local url="$2"
  local data="${3:-}"

  local args=(curl -sS --connect-timeout 5 --max-time "$TRIGGER_TIMEOUT"
              -X "$method" "$url" -H "Content-Type: application/json")
  local h
  for h in ${HEADERS+"${HEADERS[@]}"}; do
    args+=( -H "$h" )
  done
  [ -n "$data" ] && args+=( --data "$data" )

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

# Continuously polls /admin/throttle/status and appends timestamped JSON lines
# to a file.  Runs in a background sub-shell.
# Uses epoch_ms() for millisecond-resolution timestamps (portable across macOS
# and Linux) so short saturation windows are not missed.
status_sampler() {
  local out_file="$1"
  local interval_s="$2"
  while true; do
    local r
    r=$(curl_json GET "$(endpoint_url /admin/throttle/status)")
    local ts
    ts=$(epoch_ms)
    printf '%s\t%s\n' "$ts" "$(printf '%s' "$r" | awk -F '\t' '{print $3}')" >>"$out_file"
    sleep "$interval_s"
  done
}

# ── Portable millisecond timestamp ───────────────────────────────────────────
# macOS date does not support %N / %3N (GNU-only).
# Prefer gdate (brew install coreutils), then python3, then fall back to seconds.
epoch_ms() {
  if command -v gdate >/dev/null 2>&1; then
    gdate +%s%3N
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c "import time; print(int(time.time() * 1000))"
  else
    date +%s  # second resolution only — good enough if neither is available
  fi
}

# ── Argument parsing ──────────────────────────────────────────────────────────
while [ "$#" -gt 0 ]; do
  case "$1" in
    --base-url)             BASE_URL="$2";              shift 2 ;;
    --service-path)         SERVICE_PATH="$2";          shift 2 ;;
    --test-only-path)       TEST_ONLY_PATH="$2";        shift 2 ;;
    --overflow)             OVERFLOW="$2";              shift 2 ;;
    --stub-delay-ms)        STUB_DELAY_MS="$2";         shift 2 ;;
    --status-sampling-ms)   STATUS_SAMPLING_MS="$2";    shift 2 ;;
    --header)               HEADERS+=("$2");            shift 2 ;;
    -h|--help)              usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

# ── Validate numeric args ─────────────────────────────────────────────────────
for var_name in OVERFLOW STUB_DELAY_MS STATUS_SAMPLING_MS; do
  val="${!var_name}"
  if ! [[ "$val" =~ ^[0-9]+$ ]] || [ "$val" -le 0 ]; then
    echo "--$(echo "$var_name" | tr '[:upper:]_' '[:lower:]-') must be a positive integer" >&2
    exit 2
  fi
done


# Fix up path prefixes
for var_name in SERVICE_PATH TEST_ONLY_PATH; do
  val="${!var_name}"
  if [ -n "$val" ] && [[ "$val" != /* ]]; then
    printf -v "$var_name" '/%s' "$val"
  fi
done

interval_s=$(awk -v ms="$STATUS_SAMPLING_MS" 'BEGIN { printf "%.3f", ms / 1000 }')

# ── Read current max-concurrent from the live status endpoint ─────────────────
echo "════════════════════════════════════════════════════════════════"
echo " simulate_max_concurrency_timeout"
echo "════════════════════════════════════════════════════════════════"
echo
echo "Step 1 — Reading throttle configuration from /admin/throttle/status..."

initial_status_raw=$(curl_json GET "$(endpoint_url /admin/throttle/status)")
initial_status_code=$(awk -F '\t' '{print $1}' <<<"$initial_status_raw")
initial_status_body=$(awk -F '\t' '{print $3}' <<<"$initial_status_raw")

if [ "$initial_status_code" != "200" ]; then
  echo "ERROR: Could not reach /admin/throttle/status (HTTP $initial_status_code)." >&2
  echo "       Is the service running with the test-only router?" >&2
  exit 1
fi

echo "  Initial status (HTTP $initial_status_code):"
if command -v jq >/dev/null 2>&1; then
  printf '%s\n' "$initial_status_body" | jq '.'
else
  echo "  $initial_status_body"
fi

# Extract max-concurrent so we can size the burst correctly
if command -v jq >/dev/null 2>&1; then
  MAX_CONCURRENT=$(printf '%s\n' "$initial_status_body" | jq -r '.enrolmentStoreProxy.maxConcurrent // 0')
else
  # Fallback: crude grep
  MAX_CONCURRENT=$(printf '%s\n' "$initial_status_body" | grep -oE '"maxConcurrent"\s*:\s*[0-9]+' | grep -oE '[0-9]+$' || echo 0)
fi

if ! [[ "$MAX_CONCURRENT" =~ ^[0-9]+$ ]] || [ "$MAX_CONCURRENT" -le 0 ]; then
  echo "ERROR: Could not parse maxConcurrent from status response." >&2
  exit 1
fi

TOTAL_COUNT=$(( MAX_CONCURRENT + OVERFLOW ))

echo
echo "  maxConcurrent      = $MAX_CONCURRENT"
echo "  overflow           = $OVERFLOW"
echo "  totalBurst         = $TOTAL_COUNT  (will fill all slots and queue $OVERFLOW extra calls)"
echo "  stubDelayMs        = $STUB_DELAY_MS  (stub holds each connection this long)"
echo

# ── Explain what we expect to see ─────────────────────────────────────────────
echo "Step 2 — What to expect"
echo "  ┌─────────────────────────────────────────────────────────────────┐"
echo "  │  WorkItem-based concurrency control:                            │"
echo "  │  • $TOTAL_COUNT WorkItems created and persisted to MongoDB          │"
echo "  │  • First $MAX_CONCURRENT processed in parallel (max-concurrent)  │"
echo "  │  • Remaining $OVERFLOW queued until slots become available         │"
echo "  │  • Each call blocked for ~${STUB_DELAY_MS}ms by the stub           │"
echo "  │                                                                 │"
echo "  │  You should observe:                                           │"
echo "  │    ✓ currentlyProcessing  = $MAX_CONCURRENT  (saturated)             │"
echo "  │    ✓ availablePermits     = 0                                   │"
echo "  │    ✓ Total elapsed       ≈ ceil($TOTAL_COUNT / $MAX_CONCURRENT) × ${STUB_DELAY_MS}ms      │"
echo "  └─────────────────────────────────────────────────────────────────┘"
echo

# ── Temporary files ───────────────────────────────────────────────────────────
status_samples_file=$(mktemp)
trigger_out_file=$(mktemp)
trap 'rm -f "$status_samples_file" "$trigger_out_file"' EXIT

# ── Start background status sampler ──────────────────────────────────────────
echo "Step 3 — Starting status sampler (polling every ${STATUS_SAMPLING_MS}ms)..."
status_sampler "$status_samples_file" "$interval_s" &
sampler_pid=$!

# Small pause so the sampler captures at least one baseline sample
sleep 0.3

# ── Fire the burst ────────────────────────────────────────────────────────────
# ── Fire the burst ────────────────────────────────────────────────────────────
echo "Step 4 — Firing connector burst (count=$TOTAL_COUNT, stubDelayMs=$STUB_DELAY_MS)..."
echo "         The trigger POST will block until ALL $TOTAL_COUNT calls have completed"
echo "         (or timed out), which takes at least: ~$(( TOTAL_COUNT * STUB_DELAY_MS / MAX_CONCURRENT ))ms."
echo

# The stub uses ?delayMs=N to simulate a slow downstream.
# The count path segment tells EnrolmentStoreProxyThrottlingController how many
# WorkItems to create and process.
trigger_url="$(endpoint_url_with_prefix "$TEST_ONLY_PATH" "/test-only/throttle/enrolment-store-proxy/$TOTAL_COUNT")?stubDelayMs=$STUB_DELAY_MS"

start_epoch=$(date +%s)
curl_json POST "$trigger_url" >"$trigger_out_file"
end_epoch=$(date +%s)

# Stop the sampler
kill "$sampler_pid" >/dev/null 2>&1 || true
wait "$sampler_pid" 2>/dev/null || true

trigger_code=$(awk -F '\t' '{print $1}' "$trigger_out_file")
trigger_body=$(awk -F '\t' '{print $3}' "$trigger_out_file")
measured_elapsed=$(( end_epoch - start_epoch ))

# ── Results ───────────────────────────────────────────────────────────────────
echo "Step 5 — Trigger response:"
echo "  HTTP status          = $trigger_code"
echo "  Measured elapsed (s) = $measured_elapsed"
echo "  Body:"
if command -v jq >/dev/null 2>&1; then
  printf '%s\n' "$trigger_body" | jq '.'
else
  echo "  $trigger_body"
fi

echo
echo "Step 6 — Throttle observations during burst:"
if command -v jq >/dev/null 2>&1; then
  peak_inflight=$(awk -F '\t' '{print $2}' "$status_samples_file" \
    | jq -rs 'map(try fromjson catch empty)
               | map(.enrolmentStoreProxy.currentlyProcessing // 0)
               | max // 0')
  min_permits=$(awk -F '\t' '{print $2}' "$status_samples_file" \
    | jq -rs 'map(try fromjson catch empty)
               | map(.enrolmentStoreProxy.availablePermits // 100)
               | min // "n/a"')
  min_tokens=$(awk -F '\t' '{print $2}' "$status_samples_file" \
    | jq -rs 'map(try fromjson catch empty)
               | map(.enrolmentStoreProxy.tokensRemainingThisSecond)
               | map(select(. != null and . >= 0))
               | if length == 0 then "n/a" else min end')

  echo "  Peak currentlyProcessing  = $peak_inflight  (expected: $MAX_CONCURRENT)"
  echo "  Min availablePermits      = $min_permits   (expected: 0 — semaphore fully saturated)"
  echo "  Min tokensThisSecond      = $min_tokens"

  # ── Assertion ────────────────────────────────────────────────────────────────
  echo
  if [ "$peak_inflight" -ge "$MAX_CONCURRENT" ] && [ "$min_permits" -le 0 ]; then
    echo "  ✅  PASS — Max concurrency was reached and semaphore saturated as expected."
    echo "            Overflow calls queued behind the semaphore gate while the stub"
    echo "            held connections open past the connector timeout."
  else
    echo "  ⚠️   WARN — Peak in-flight ($peak_inflight) did not reach maxConcurrent ($MAX_CONCURRENT)," >&2
    echo "             or availablePermits never reached 0 (min observed: $min_permits)." >&2
    echo "             Try increasing --stub-delay-ms or --overflow." >&2
  fi
else
  if command -v python3 >/dev/null 2>&1; then
    read -r peak_inflight min_permits min_tokens < <(
      python3 - "$status_samples_file" <<'PY'
import json
import sys

path = sys.argv[1]
peak = 0
min_permits = None
min_tokens = None

with open(path, "r", encoding="utf-8") as f:
    for line in f:
        parts = line.rstrip("\n").split("\t", 1)
        if len(parts) != 2:
            continue
        try:
            payload = json.loads(parts[1])
        except Exception:
            continue

        esp = payload.get("enrolmentStoreProxy", {})
        inflight = int(esp.get("currentlyProcessing", 0))
        permits = int(esp.get("availablePermits", 10**9))
        tokens = esp.get("tokensRemainingThisSecond", None)

        if inflight > peak:
            peak = inflight
        if min_permits is None or permits < min_permits:
            min_permits = permits
        if isinstance(tokens, int) and tokens >= 0:
            if min_tokens is None or tokens < min_tokens:
                min_tokens = tokens

print(
    peak,
    (min_permits if min_permits is not None else "n/a"),
    (min_tokens if min_tokens is not None else "n/a")
)
PY
    )

    echo "  jq not installed — python3 fallback summary from all samples:"
    echo "  Peak currentlyProcessing  = $peak_inflight  (expected: $MAX_CONCURRENT)"
    echo "  Min availablePermits      = $min_permits   (expected: 0 — semaphore fully saturated)"
    echo "  Min tokensThisSecond      = $min_tokens"

    echo
    if [ "$peak_inflight" -ge "$MAX_CONCURRENT" ] && [ "$min_permits" -le 0 ]; then
      echo "  ✅  PASS — Max concurrency was reached and semaphore saturated as expected."
    else
      echo "  ⚠️   WARN — Peak in-flight ($peak_inflight) did not reach maxConcurrent ($MAX_CONCURRENT)," >&2
      echo "             or availablePermits never reached 0 (min observed: $min_permits)." >&2
    fi

    echo "  Last 10 raw samples (for context):"
    tail -n 10 "$status_samples_file" | awk -F '\t' '{print "  [" $1 "] " $2}'
  else
    echo "  jq/python3 not installed — showing last 10 raw status samples:"
    tail -n 10 "$status_samples_file" | awk -F '\t' '{print "  [" $1 "] " $2}'
  fi
fi

echo
echo "Step 7 — Final throttle status (after burst):"
final_raw=$(curl_json GET "$(endpoint_url /admin/throttle/status)")
final_code=$(awk -F '\t' '{print $1}' <<<"$final_raw")
final_body=$(awk -F '\t' '{print $3}' <<<"$final_raw")
echo "  HTTP $final_code"
if command -v jq >/dev/null 2>&1; then
  printf '%s\n' "$final_body" | jq '.'
else
  echo "  $final_body"
fi

echo
echo "════════════════════════════════════════════════════════════════"
echo " Simulation complete."
echo " Elapsed: ${measured_elapsed}s"
echo "════════════════════════════════════════════════════════════════"









