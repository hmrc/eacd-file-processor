#!/usr/bin/env bash
set -euo pipefail

BASE_URL="http://localhost:9867"
SERVICE_PATH="/eacd-file-processor"
TEST_ONLY_PATH=""
COUNT=5
TIMEOUT=120
STATUS_SAMPLING_MS=200

declare -a HEADERS=()

usage() {
  cat <<'EOF'
Usage: scripts/simulate_enrolment_store_proxy_throttling.sh [options]

Calls the test-only trigger endpoint that fires N connector calls concurrently
through EnrolmentStoreProxyConnector, then samples /admin/throttle/status while
it runs.

Run service with testOnly router first:
  sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes"

Options:
  --base-url URL            Service base URL (default: http://localhost:9867)
  --service-path PATH       App route prefix (default: /eacd-file-processor)
  --test-only-path PATH     Test-only route prefix (default: empty)
  --count N                 Number of connector calls to trigger (default: 25)
  --timeout SEC             Curl timeout in seconds (default: 120)
  --status-sampling-ms MS   Poll interval for /admin/throttle/status (default: 200)
  --header "Key: Value"      Extra header for all calls (repeatable)
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

echo "Initial throttle status:"
curl_json GET "$(endpoint_url /admin/throttle/status)" | awk -F '\t' '{print "  status=" $1 " body=" $3}'

echo
echo "Triggering throttled connector burst (count=$COUNT)..."
status_sampler "$status_samples_file" "$interval_s" &
sampler_pid=$!

start=$(date +%s)
curl_json POST "$(endpoint_url_with_prefix "$TEST_ONLY_PATH" "/test-only/throttle/enrolment-store-proxy/$COUNT")" >"$trigger_out_file"
end=$(date +%s)

kill "$sampler_pid" >/dev/null 2>&1 || true
wait "$sampler_pid" 2>/dev/null || true

code=$(awk -F '\t' '{print $1}' "$trigger_out_file")
body=$(awk -F '\t' '{print $3}' "$trigger_out_file")
elapsed=$((end - start))

echo "Trigger response:"
echo "  status=$code"
echo "  body=$body"
echo "  measuredElapsedSeconds=$elapsed"

if command -v jq >/dev/null 2>&1; then
  max_inflight=$(awk -F '\t' '{print $2}' "$status_samples_file" | jq -rs 'map(try fromjson catch empty) | map(.enrolmentStoreProxy.currentlyProcessing // 0) | max // 0')
  min_tokens=$(awk -F '\t' '{print $2}' "$status_samples_file" | jq -rs 'map(try fromjson catch empty) | map(.enrolmentStoreProxy.tokensRemainingThisSecond) | map(select(. != null and . >= 0)) | if length == 0 then "n/a" else min end')
  echo "Observed throttling:"
  echo "  maxEnrolmentStoreProxyInFlight=$max_inflight"
  echo "  minEnrolmentStoreProxyTokens=$min_tokens"
else
  echo "Observed throttling: jq not installed; last 5 samples:"
  tail -n 5 "$status_samples_file" | awk -F '\t' '{print "  " $2}'
fi

echo
echo "Final throttle status:"
curl_json GET "$(endpoint_url /admin/throttle/status)" | awk -F '\t' '{print "  status=" $1 " body=" $3}'

if [ "$code" -ne 200 ]; then
  exit 1
fi

