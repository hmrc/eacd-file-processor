#!/usr/bin/env bash
# =============================================================================
# SCENARIO TESTING GUIDE FOR WORKITEM-BASED CONCURRENCY CONTROL
# =============================================================================
#
# This file documents all testing scenarios for the new WorkItem-based
# throttling mechanism. Each scenario tests a specific aspect of the
# concurrency control system.
#
# Quick reference:
#   • Default max-concurrent = 4 (configurable in conf/application.conf)
#   • Each WorkItem represents one file notification to enrolment-store-proxy
#   • MongoDB stores WorkItems; concurrency enforced during processing
#   • Metrics available at: GET /admin/throttle/status
#
# =============================================================================

# ============================================================================
# SETUP — Prerequisites for all scenarios
# ============================================================================

To run ANY scenario, first:

1. Start MongoDB (if not already running):
   brew services start mongodb-community
   # OR
   docker run --name mongodb -p 27017:27017 -d mongo:latest

2. Start the service with test-only router:
   cd /path/to/eacd-file-processor
   sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes"

3. (Optional) Install jq for prettier output:
   brew install jq

4. Make scripts executable:
   chmod +x scripts/*.sh

# ============================================================================
# SCENARIO 1: BASIC CONCURRENCY CAPPING
# ============================================================================

Purpose:   Verify that concurrency never exceeds max-concurrent

Time:      ~5 seconds
Difficulty: Easy
Useful for: Smoke testing, first-time validation

Command:
  ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 5

What happens:
  1. 5 WorkItems are created and persisted to MongoDB
  2. All 5 are processed in a single chunk (since 5 ≤ default max-concurrent=4)
    Actually: first 4 processed in parallel, 5th queued, then processed
  3. Metrics sampled every 200ms capture the execution

Expected observations:
  ✓ Trigger response HTTP 200
  ✓ Peak in-flight requests ≤ 4
  ✓ Total elapsed time ≈ 2 * avg_response_time (2 chunks)

Validation questions:
  Q: Did peak concurrency stay at or below max-concurrent?
  Q: Did the process complete successfully (HTTP 200)?

# ============================================================================
# SCENARIO 2: MULTIPLE SEQUENTIAL CHUNKS
# ============================================================================

Purpose:   Demonstrate chunk-by-chunk processing over multiple batches

Time:      ~10 seconds
Difficulty: Easy
Useful for: Understanding batch boundaries

Command:
  ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 50

What happens:
  1. 50 WorkItems created
  2. Chunked into groups of 4 (assuming max-concurrent=4):
     Chunk 1: items 1-4   (parallel)
     Chunk 2: items 5-8   (parallel, serial after chunk 1)
     Chunk 3: items 9-12  (parallel, serial after chunk 2)
     ... and so on for 13 chunks total
  3. Each chunk fully completes before the next starts

Expected observations:
  ✓ Peak concurrency = 4 (stable throughout)
  ✓ Avg concurrency ≈ 4 (saturated)
  ✓ Total elapsed ≈ 13 * avg_response_time / 1000 seconds
  ✓ Visible chunk transitions in status samples

Validation questions:
  Q: Was concurrency consistently at max-concurrent?
  Q: Did elapsed time scale linearly with chunk count?

Formula:
  total_time ≈ ceil(count / max-concurrent) * avg_response_time_per_call
  For 50 items at 4 concurrent, ~100ms per call: ≈ 13 * 100ms ≈ 1.3s

# ============================================================================
# SCENARIO 3: SLOW DOWNSTREAM (Stub Delay)
# ============================================================================

Purpose:   Observe concurrency control under realistic latency conditions

Time:      ~25 seconds
Difficulty: Easy
Useful for: Understanding latency impact

Command:
  ./scripts/simulate_enrolment_store_proxy_throttling.sh \
    --count 20 \
    --stub-delay-ms 1000

What happens:
  1. 20 WorkItems created
  2. The stub endpoint delays each response by 1000ms (simulating slow downstream)
  3. Concurrency remains capped at max-concurrent despite the latency
  4. Chunks process sequentially: as soon as one call completes, next queued item starts

Expected observations:
  ✓ currentlyProcessing stays at 4 while items remain queued
  ✓ availablePermits reaches 0 (all slots occupied)
  ✓ Total elapsed ≈ ceil(20 / 4) * 1000ms = 5000ms
  ✓ Peak concurrency = 4

Real-world relevance:
  This demonstrates that the system properly handles slow downstream services.
  The concurrency limit ensures the downstream is never bombarded, and processing
  time scales predictably based on:
    • Number of items (20)
    • Downstream latency (1000ms)
    • Concurrency limit (4)

Validation questions:
  Q: Did total time match the formula?
  Q: Did concurrency remain steady despite the delays?

# ============================================================================
# SCENARIO 4: CONCURRENCY SATURATION (Overflow)
# ============================================================================

Purpose:   Test that overflow WorkItems are queued correctly

Time:      ~10 seconds
Difficulty: Intermediate
Useful for: Testing queue behavior and MongoDB persistence

Command:
  ./scripts/simulate_max_concurrency_timeout.sh \
    --overflow 3 \
    --stub-delay-ms 2000

What happens:
  1. Service reports max-concurrent = 4 from /admin/throttle/status
  2. Script creates 4 + 3 = 7 total WorkItems
  3. First 4 start processing immediately (in parallel)
  4. Remaining 3 are queued in MongoDB (ProcessingStatus.Pending)
  5. As each of the first 4 completes, next queued item starts
  6. This continues until all 7 are done

Expected observations:
  ✓ currentlyProcessing reaches 4 and holds
  ✓ availablePermits drops to 0 (fully saturated)
  ✓ Remaining 3 items queue correctly in MongoDB
  ✓ Total elapsed ≈ ceil(7 / 4) * 2000ms ≈ 4000ms

MongoDB inspection during/after test:
  db.workItems.find({}, {batchId: 1, processingStatus: 1})
  
  Expected states:
  • During processing: some Pending, some InProgress
  • After completion: all Succeeded (or Failed if errors occurred)

Validation questions:
  Q: Did availablePermits reach 0?
  Q: Were overflow items visible in MongoDB with Pending status?
  Q: Did processing complete successfully?

# ============================================================================
# SCENARIO 5: EXTREME SCALE (High Volume)
# ============================================================================

Purpose:   Verify stability under high load

Time:      ~30-60 seconds
Difficulty: Intermediate
Useful for: Performance validation, stress testing

Command:
  ./scripts/simulate_enrolment_store_proxy_throttling.sh \
    --count 200 \
    --status-sampling-ms 500

What happens:
  1. 200 WorkItems created (50 chunks if max-concurrent=4)
  2. Processed sequentially in batches of 4
  3. Status sampled every 500ms to reduce noise
  4. MongoDB stores/manages all 200 items

Expected observations:
  ✓ Peak concurrency = 4 (stable for all 50 chunks)
  ✓ No memory exhaustion or timeouts
  ✓ Linear time scaling: 50 chunks * avg_time_per_chunk
  ✓ Total elapsed ≈ 50 * avg_response_time

Performance metrics to track:
  • Service CPU usage (should be low — mostly waiting)
  • MongoDB CPU (should handle easily)
  • No obvious memory leaks
  • No "too many open connections" errors

Validation questions:
  Q: Did the system remain stable throughout?
  Q: Was concurrency consistent at 4 for all chunks?
  Q: Did completion succeed (HTTP 200)?

# ============================================================================
# SCENARIO 6: SERIAL PROCESSING (max-concurrent=1)
# ============================================================================

Purpose:   Validate behavior when concurrency is minimal

Time:      Variable (depends on count)
Difficulty: Intermediate
Useful for: Edge case testing

Setup:
  1. Stop current service
  2. Start with max-concurrent=1:
     sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes \
          -Dthrottle.enrolment-store-proxy.max-concurrent=1"
  3. Run scenario:
     ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 10

What happens:
  1. 10 WorkItems queued sequentially
  2. Only 1 can be in-flight at a time
  3. Purely serial processing — no parallelism

Expected observations:
  ✓ Peak concurrency = 1
  ✓ Avg concurrency ≈ 1
  ✓ Total elapsed ≈ 10 * avg_response_time (no parallelism benefit)
  ✓ availablePermits = 0 whenever items remain

Comparison:
  With max-concurrent=4:  10 items ÷ 4 ≈ 3 batches ≈ 3x faster
  With max-concurrent=1:  10 items = 10 batches ≈ baseline

Validation questions:
  Q: Did concurrency truly stay at 1?
  Q: Was elapsed time ~4x longer than with max-concurrent=4?

# ============================================================================
# SCENARIO 7: VARYING max-concurrent VALUES
# ============================================================================

Purpose:   Compare performance across different concurrency limits

Time:      15-20 seconds per test
Difficulty: Intermediate
Useful for: Tuning and optimization

Procedure:
  Run the same workload with different max-concurrent values and compare:

  Test 1: max-concurrent=2
    sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes \
         -Dthrottle.enrolment-store-proxy.max-concurrent=2"
    ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 20

  Test 2: max-concurrent=4
    sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes \
         -Dthrottle.enrolment-store-proxy.max-concurrent=4"
    ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 20

  Test 3: max-concurrent=8
    sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes \
         -Dthrottle.enrolment-store-proxy.max-concurrent=8"
    ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 20

Expected pattern:
  max-concurrent=2:  10 chunks,  total_time ≈ 10x baseline (if ~100ms per call)
  max-concurrent=4:   5 chunks,  total_time ≈ 5x baseline
  max-concurrent=8:   3 chunks,  total_time ≈ 3x baseline

  (Higher max-concurrent = fewer chunks = faster completion)

Validation questions:
  Q: Did elapsed time scale inversely with max-concurrent?
  Q: Were there any stability issues at higher concurrency?
  Q: Is there an optimal value for your downstream service?

Tuning guidance:
  • Start conservative (max-concurrent=2-4)
  • Gradually increase and monitor downstream service health
  • Watch for upstream/downstream bottlenecks
  • Consider downstream resource constraints

# ============================================================================
# SCENARIO 8: FAILURE RESILIENCE (Optional)
# ============================================================================

Purpose:   Test that failed requests are properly marked in MongoDB

Time:      ~10 seconds
Difficulty: Advanced
Useful for: Error handling validation

Setup:
  Configure a stub endpoint that returns errors, or:
  Stop the stub and let requests timeout

Expected observations:
  ✓ Failed WorkItems marked with ProcessingStatus.Failed
  ✓ Concurrency maintained (failed ≠ stuck)
  ✓ No cascading failures
  ✓ Error logs visible in service logs

MongoDB inspection:
  db.workItems.find({processingStatus: "Failed"})

# ============================================================================
# QUICK REFERENCE: Running Scenarios
# ============================================================================

Start fresh:
  # Terminal 1: MongoDB
  brew services start mongodb-community

  # Terminal 2: Service
  cd /path/to/eacd-file-processor
  sbt "run -Dapplication.router=testOnlyDoNotUseInAppConf.Routes"

  # Terminal 3: Run scenarios
  chmod +x scripts/*.sh
  ./scripts/run_all_scenarios.sh

Individual scenarios:
  Scenario 1: ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 5
  Scenario 2: ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 50
  Scenario 3: ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 20 --stub-delay-ms 1000
  Scenario 4: ./scripts/simulate_max_concurrency_timeout.sh --overflow 3 --stub-delay-ms 2000
  Scenario 5: ./scripts/simulate_enrolment_store_proxy_throttling.sh --count 200

Monitor progress:
  curl http://localhost:9867/eacd-file-processor/admin/throttle/status | jq .

# ============================================================================
# INTERPRETING RESULTS
# ============================================================================

Key metrics from /admin/throttle/status:

{
  "enrolmentStoreProxy": {
    "maxConcurrent": 4,              ← Configured limit
    "availablePermits": 2,           ← Free slots (maxConcurrent - currentlyProcessing)
    "currentlyProcessing": 2,        ← In-flight requests NOW
    "maxPerSecond": 0,               ← Unused (always 0 in WorkItem mode)
    "tokensRemainingThisSecond": -1  ← Unused (always -1 in WorkItem mode)
  }
}

Interpretation:
  • currentlyProcessing = 0: Idle (no requests in flight)
  • currentlyProcessing < maxConcurrent: Processing but not saturated
  • currentlyProcessing = maxConcurrent: Saturated (all slots in use)
  • availablePermits = 0: All concurrency slots occupied
  • Peak concurrency during burst should = maxConcurrent

# ============================================================================
# TROUBLESHOOTING
# ============================================================================

Script fails immediately:
  → Check service is running: curl http://localhost:9867/eacd-file-processor/admin/throttle/status
  → Check MongoDB is running: mongosh localhost:27017

Concurrency never reaches limit:
  → Increase --count or --stub-delay-ms
  → Check max-concurrent in conf/application.conf
  → Review service logs for errors

WorkItems stuck in Pending:
  → Check service logs for errors
  → Verify MongoDB connection
  → Restart service and try again

Results don't match expectations:
  → Check /admin/throttle/status to see actual maxConcurrent
  → Verify stub delay is applied: check request logs
  → Ensure no other load on the service

# ============================================================================

