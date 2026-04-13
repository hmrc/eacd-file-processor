#!/usr/bin/env bash
# =============================================================================
# simulate_max_concurrency_timeout.sh
#
# Demonstrates the scenario where the enrolment-store-proxy stub deliberately
# times out, causing the semaphore (max-concurrent) to be held for the full
# stub response duration.  Because all concurrency slots are occupied, every
# additional connector call queues behind the semaphore gate.
#
# What the script does
# ────────────────────
#  1. Reads the configured max-concurrent value from /admin/throttle/status.
#  2. Fires (max-concurrent + OVERFLOW) connector calls through the service by
#     hitting POST /test-only/throttle/enrolment-store-proxy/:count.
#     Each call fans out through EnrolmentStoreProxyConnector → ThrottlingService
#     → stub endpoint.
#  3. The stub endpoint is started with a delay (--stub-delay-ms) that is
#     longer than the connector's HTTP response deadline (--connector-timeout-ms),
#     so every in-flight request times out inside the connector.
#  4. Meanwhile, /admin/throttle/status is polled every STATUS_SAMPLING_MS so
#     you can observe:
#       • currentlyProcessing  → climbs to maxConcurrent and stays there
#       • availablePermits     → drops to 0
#       • overflow calls       → remain blocked behind the semaphore
#  5. At the end, observed peak concurrency, min available permits, and the
#     trigger response are printed.
#
# Pre-requisites
# ──────────────
#  • Service running with test-only router:
#      sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes"
#  • The stub must be the SLOW variant that accepts a ?delayMs= query parameter.
#    See EnrolmentStoreProxyStubController.receiveFileNotification (slow mode).
#  • jq recommended for structured output (falls back to raw JSON if absent).
#
# Usage example
# ─────────────
#  scripts/simulate_max_concurrency_timeout.sh \
#      --overflow 3 \
#      --stub-delay-ms 8000 \
#      --connector-timeout-ms 3000 \
#      --status-sampling-ms 300
# =============================================================================
set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
BASE_URL="http://localhost:9867"
SERVICE_PATH="/eacd-file-processor"
TEST_ONLY_PATH=""
OVERFLOW=3                  # extra calls beyond max-concurrent
STUB_DELAY_MS=8000          # ms the stub holds the connection open
CONNECTOR_TIMEOUT_MS=3000   # ms after which the connector HTTP call times out
STATUS_SAMPLING_MS=300      # poll interval for /admin/throttle/status
TRIGGER_TIMEOUT=120         # curl max-time for the trigger POST itself

declare -a HEADERS=()

# ── Helpers ───────────────────────────────────────────────────────────────────
usage() {
  cat <<'EOF'
Usage: scripts/simulate_max_concurrency_timeout.sh [options]

Fills every concurrency slot in the throttle semaphore with stub calls that
intentionally hold the connection open (simulating a slow/timing-out downstream),
proving that:
  • currentlyProcessing reaches maxConcurrent
  • availablePermits drops to 0
  • overflow requests queue behind the semaphore gate

IMPORTANT — rate-limiter interaction:
  The per-second rate limiter (max-per-second) drips calls into the semaphore
  one window at a time.  For ALL concurrency slots to be occupied simultaneously,
  stub-delay-ms must be longer than (1000 / max-per-second) * maxConcurrent so
  early calls are still in-flight when later ones arrive at the semaphore.

  For the cleanest demonstration, start the service with max-per-second = 0
  (unlimited) so all calls hit the semaphore at the same instant:
    sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes \
             -Dthrottle.enrolment-store-proxy.max-per-second=0"

Options:
  --base-url URL              Service base URL (default: http://localhost:9867)
  --service-path PATH         App route prefix (default: /eacd-file-processor)
  --test-only-path PATH       Test-only route prefix (default: empty)
  --overflow N                Extra calls above max-concurrent (default: 3)
  --stub-delay-ms MS          How long the stub holds each connection (default: 8000)
                              Must be longer than the inter-call gap introduced by
                              the rate limiter, or set max-per-second=0 to bypass it.
  --connector-timeout-ms MS   Logged in output only — the actual HTTP timeout is
                              governed by Play WS config, not this flag (default: 3000).
                              To enforce it at runtime start the service with:
                                -Dplay.ws.timeout.request=3000
  --status-sampling-ms MS     Status poll interval in milliseconds (default: 300)
  --header "Key: Value"       Extra header for all calls (repeatable)
  -h, --help                  Show this help text
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
    --connector-timeout-ms) CONNECTOR_TIMEOUT_MS="$2";  shift 2 ;;
    --status-sampling-ms)   STATUS_SAMPLING_MS="$2";    shift 2 ;;
    --header)               HEADERS+=("$2");            shift 2 ;;
    -h|--help)              usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

# ── Validate numeric args ─────────────────────────────────────────────────────
for var_name in OVERFLOW STUB_DELAY_MS CONNECTOR_TIMEOUT_MS STATUS_SAMPLING_MS; do
  val="${!var_name}"
  if ! [[ "$val" =~ ^[0-9]+$ ]] || [ "$val" -le 0 ]; then
    echo "--$(echo "$var_name" | tr '[:upper:]_' '[:lower:]-') must be a positive integer" >&2
    exit 2
  fi
done

if [ "$CONNECTOR_TIMEOUT_MS" -ge "$STUB_DELAY_MS" ]; then
  echo "WARNING: --connector-timeout-ms ($CONNECTOR_TIMEOUT_MS) >= --stub-delay-ms ($STUB_DELAY_MS)." >&2
  echo "         Requests may complete before the stub times out.  For the saturation" >&2
  echo "         scenario, set connector-timeout-ms < stub-delay-ms." >&2
fi

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

# Extract maxPerSecond — needed to explain the rate-limiter / semaphore interaction
if command -v jq >/dev/null 2>&1; then
  MAX_PER_SECOND=$(printf '%s\n' "$initial_status_body" | jq -r '.enrolmentStoreProxy.maxPerSecond // 0')
else
  MAX_PER_SECOND=$(printf '%s\n' "$initial_status_body" | grep -oE '"maxPerSecond"\s*:\s*[0-9]+' | grep -oE '[0-9]+$' || echo 0)
fi
MAX_PER_SECOND="${MAX_PER_SECOND:-0}"

TOTAL_COUNT=$(( MAX_CONCURRENT + OVERFLOW ))

echo
echo "  maxConcurrent      = $MAX_CONCURRENT"
echo "  maxPerSecond       = $MAX_PER_SECOND  (0 = unlimited)"
echo "  overflow           = $OVERFLOW"
echo "  totalBurst         = $TOTAL_COUNT  (will fill all slots and queue $OVERFLOW extra calls)"
echo "  stubDelayMs        = $STUB_DELAY_MS  (stub holds each connection this long)"
echo "  connectorTimeoutMs = $CONNECTOR_TIMEOUT_MS  (advisory — see --help)"
echo

# ── Pre-flight: rate-limiter interaction check ────────────────────────────────
# The token bucket drips calls into the semaphore N-per-second.  For the semaphore
# to be fully saturated you need ALL maxConcurrent calls to be in-flight at the
# same time.  With max-per-second=N the last of the initial maxConcurrent calls
# only arrives at the semaphore after ceil(maxConcurrent/N) seconds.  The earlier
# calls must still be holding their permits at that point.
#
#   Required:  stub-delay-ms  >  ceil(maxConcurrent / max-per-second) * 1000
#
# If this condition is not met the semaphore will never be fully saturated and
# the script will not demonstrate the max-concurrency scenario.
if [ "$MAX_PER_SECOND" -gt 0 ] 2>/dev/null; then
  # ceil(MAX_CONCURRENT / MAX_PER_SECOND) in integer arithmetic
  windows_needed=$(( (MAX_CONCURRENT + MAX_PER_SECOND - 1) / MAX_PER_SECOND ))
  min_stub_ms=$(( windows_needed * 1000 ))

  echo "  ⚠️  Rate-limiter is ACTIVE  (max-per-second=$MAX_PER_SECOND)"
  echo "      The token bucket releases at most $MAX_PER_SECOND call(s) per second."
  echo "      All $MAX_CONCURRENT concurrency slots are first occupied after ~${windows_needed}s."
  echo "      stub-delay-ms must be > ${min_stub_ms}ms for the semaphore to stay"
  echo "      fully saturated long enough to be observed."
  echo

  if [ "$STUB_DELAY_MS" -le "$min_stub_ms" ]; then
    echo "  ❌  PROBLEM: stub-delay-ms ($STUB_DELAY_MS) is NOT greater than ${min_stub_ms}ms."
    echo "      The first calls will time out or complete BEFORE all concurrency slots"
    echo "      are filled.  You will see currentlyProcessing < maxConcurrent."
    echo
    echo "      Fix — choose ONE of:"
    echo "        a) Restart the service with max-per-second=0 (recommended):"
    echo "             sbt \"run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes \\"
    echo "                      -Dthrottle.enrolment-store-proxy.max-per-second=0\""
    echo "        b) Increase --stub-delay-ms to at least $(( min_stub_ms + 1000 )):"
    echo "             --stub-delay-ms $(( min_stub_ms + 1000 ))"
    echo
  else
    echo "      stub-delay-ms ($STUB_DELAY_MS) > minimum required (${min_stub_ms}ms) ✓"
    echo "      The semaphore should saturate — but consider max-per-second=0 for"
    echo "      a cleaner, instantaneous saturation demonstration."
    echo
  fi
else
  echo "  ✓  Rate-limiter is DISABLED (max-per-second=0 or unlimited)."
  echo "      All $TOTAL_COUNT calls will hit the semaphore simultaneously — ideal"
  echo "      for demonstrating max-concurrency saturation."
  echo
fi

# ── Explain what we expect to see ─────────────────────────────────────────────
echo "Step 2 — What to expect"
if [ "${MAX_PER_SECOND:-0}" -eq 0 ] 2>/dev/null; then
  echo "  ┌─────────────────────────────────────────────────────────────────┐"
  echo "  │  Rate limiter OFF — all $TOTAL_COUNT calls hit the semaphore at once.  │"
  echo "  │  The first $MAX_CONCURRENT acquire a permit; $OVERFLOW are queued immediately.│"
  echo "  │  The stub holds each connection for ${STUB_DELAY_MS}ms.              │"
  echo "  │                                                                 │"
  echo "  │  You should observe (almost immediately):                      │"
  echo "  │    currentlyProcessing  = $MAX_CONCURRENT  (saturated)                  │"
  echo "  │    availablePermits     = 0                                    │"
  echo "  └─────────────────────────────────────────────────────────────────┘"
else
  windows_needed=$(( (MAX_CONCURRENT + MAX_PER_SECOND - 1) / MAX_PER_SECOND ))
  echo "  ┌─────────────────────────────────────────────────────────────────┐"
  echo "  │  Rate limiter ON (max-per-second=$MAX_PER_SECOND).                      │"
  echo "  │  Calls drip in $MAX_PER_SECOND per second.  All $MAX_CONCURRENT slots occupied after  │"
  echo "  │  ~${windows_needed}s.  Semaphore saturation visible between t=${windows_needed}s and      │"
  echo "  │  t=(when first calls complete/timeout).                        │"
  echo "  │                                                                 │"
  echo "  │  You should observe BRIEFLY:                                   │"
  echo "  │    currentlyProcessing  = $MAX_CONCURRENT                               │"
  echo "  │    availablePermits     = 0                                    │"
  echo "  │                                                                 │"
  echo "  │  If you miss the window, restart with max-per-second=0.       │"
  echo "  └─────────────────────────────────────────────────────────────────┘"
fi
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
echo
echo "Step 4 — Firing connector burst (count=$TOTAL_COUNT, stubDelayMs=$STUB_DELAY_MS)..."
echo "         The trigger POST will block until ALL $TOTAL_COUNT calls have completed"
echo "         (or timed out), which takes at least: ${CONNECTOR_TIMEOUT_MS}ms."
echo

# The stub uses ?delayMs=N to simulate a slow downstream.
# The count path segment tells EnrolmentStoreProxyThrottlingController how many
# concurrent connector calls to launch.
trigger_url="$(endpoint_url_with_prefix "$TEST_ONLY_PATH" "/test-only/throttle/enrolment-store-proxy/$TOTAL_COUNT")?stubDelayMs=$STUB_DELAY_MS&connectorTimeoutMs=$CONNECTOR_TIMEOUT_MS"

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









