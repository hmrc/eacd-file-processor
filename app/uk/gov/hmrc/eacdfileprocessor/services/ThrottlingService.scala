/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.eacdfileprocessor.services

import play.api.Logging
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
 * ThrottlingService applies TWO independent controls to every outbound call made
 * through [[uk.gov.hmrc.eacdfileprocessor.connectors.EnrolmentStoreProxyConnector]]:
 *
 * 1. TOKEN BUCKET  — limits requests *per second*
 *    Config: throttle.enrolment-store-proxy.max-per-second = 2
 *    Effect: at most N new requests are started each second.
 *            Excess requests block until the next second's budget refills.
 *            Set to 0 for unlimited.
 *
 * 2. SEMAPHORE     — limits *concurrent* (simultaneous) requests
 *    Config: throttle.enrolment-store-proxy.max-concurrent = 5
 *    Effect: at most N requests are in-flight at the same time.
 *            Further requests wait until one finishes.
 *
 * Both gates are applied in order — a request must pass both to proceed:
 *
 *   Request → [Token Bucket gate] → [Semaphore gate] → executes → releases Semaphore
 *
 * This is the ONLY place throttling is applied in this service.
 */
@Singleton
class ThrottlingService @Inject()(appConfig: AppConfig) extends Logging {

  // ── Semaphore (concurrency control for Enrolment Store Proxy) ─────────────
  private val enrolmentStoreProxySemaphore: Semaphore =
    new Semaphore(appConfig.maxConcurrentEnrolmentStoreProxyRequests, true)

  // ── Token Bucket (per-second rate control for Enrolment Store Proxy) ──────
  private val enrolmentStoreProxyRateLimiter: TokenBucket =
    new TokenBucket(appConfig.maxPerSecondEnrolmentStoreProxyRequests)

  // ── Public API ────────────────────────────────────────────────────────────

  /**
   * Wraps an outbound call to enrolment-store-proxy with both throttle gates.
   *
   * Usage (inside EnrolmentStoreProxyConnector):
   * {{{
   *   throttlingService.throttleEnrolmentStoreProxyCall {
   *     httpClient.get(...).execute[HttpResponse]
   *   }
   * }}}
   */
  def throttleEnrolmentStoreProxyCall[T](operation: => Future[T])(implicit ec: ExecutionContext): Future[T] =
    throttle(operation, enrolmentStoreProxyRateLimiter, enrolmentStoreProxySemaphore, "EnrolmentStoreProxy")

  /**
   * Returns a snapshot of current throttling state for the monitoring endpoint.
   */
  def getThrottlingStatus: ThrottlingStatus =
    ThrottlingStatus(
      enrolmentStoreProxy = ServiceThrottleState(
        maxConcurrent             = appConfig.maxConcurrentEnrolmentStoreProxyRequests,
        availablePermits          = enrolmentStoreProxySemaphore.availablePermits(),
        currentlyProcessing       = appConfig.maxConcurrentEnrolmentStoreProxyRequests - enrolmentStoreProxySemaphore.availablePermits(),
        maxPerSecond              = appConfig.maxPerSecondEnrolmentStoreProxyRequests,
        tokensRemainingThisSecond = enrolmentStoreProxyRateLimiter.availableTokens
      )
    )

  // ── Private implementation ────────────────────────────────────────────────

  private def throttle[T](
    operation:   => Future[T],
    rateLimiter: TokenBucket,
    semaphore:   Semaphore,
    name:        String
  )(implicit ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()

    // Each call is dispatched onto its own thread from the pool via Future { }.
    // Without this outer Future, every call to throttle() runs synchronously on
    // the *calling* thread (the Play Action dispatcher thread).  That means the
    // calls in a burst are evaluated one-by-one, and the first call that blocks
    // on semaphore.acquire() prevents all subsequent calls from even starting —
    // so currentlyProcessing can never exceed 1 regardless of max-concurrent.
    //
    // By wrapping in Future { scala.concurrent.blocking { ... } } each call gets
    // its own thread.  They all race to the semaphore simultaneously; the first
    // maxConcurrent callers acquire permits and proceed, while the remainder
    // block on their own threads until a permit is released.
    Future {
      scala.concurrent.blocking {
        try {
          rateLimiter.acquire(name)    // gate 1: per-second rate limit
          semaphore.acquire()          // gate 2: concurrency limit
          logger.debug(s"[$name] Both throttle gates passed. Executing request.")

          operation.onComplete { result =>
            try {
              semaphore.release()
              logger.debug(s"[$name] Semaphore released.")
            } finally {
              promise.complete(result)
            }
          }
        } catch {
          case e: InterruptedException =>
            logger.error(s"[$name] Thread interrupted during throttling", e)
            Thread.currentThread().interrupt()
            promise.failure(new RuntimeException(s"[$name] Throttling interrupted", e))
          case e: Exception =>
            logger.error(s"[$name] Error during throttling: ${e.getMessage}", e)
            promise.failure(e)
        }
      }
    }

    promise.future
  }
}

// ── Token Bucket ──────────────────────────────────────────────────────────────

/**
 * Thread-safe Token Bucket for per-second rate limiting.
 *
 * How it works:
 *   - The bucket holds up to `ratePerSecond` tokens.
 *   - At the start of every new 1-second window the bucket is fully refilled.
 *   - Each request consumes one token.
 *   - If the bucket is empty the caller BLOCKS until the next window refills it.
 *
 * Setting ratePerSecond = 0 disables rate limiting entirely (unlimited mode).
 */
class TokenBucket(ratePerSecond: Int) extends Logging {

  private val windowStart: AtomicLong    = new AtomicLong(System.currentTimeMillis())
  private val tokensUsed:  AtomicInteger = new AtomicInteger(0)

  def acquire(serviceName: String): Unit = {
    if (ratePerSecond <= 0) return

    var acquired = false
    while (!acquired) {
      val now         = System.currentTimeMillis()
      val windowBegin = windowStart.get()
      val elapsed     = now - windowBegin

      if (elapsed >= 1000L) {
        if (windowStart.compareAndSet(windowBegin, now)) {
          tokensUsed.set(0)
        }
      } else {
        val used = tokensUsed.get()
        if (used < ratePerSecond) {
          if (tokensUsed.compareAndSet(used, used + 1)) {
            logger.debug(s"[$serviceName] Rate token acquired (${used + 1}/$ratePerSecond this second)")
            acquired = true
          }
        } else {
          val waitMs = 1000L - elapsed
          logger.debug(s"[$serviceName] Rate limit ($ratePerSecond/sec) reached. Waiting ${waitMs}ms for next window.")
          try Thread.sleep(waitMs.max(1L))
          catch { case _: InterruptedException => Thread.currentThread().interrupt() }
        }
      }
    }
  }

  def availableTokens: Int = {
    if (ratePerSecond <= 0) return -1
    val elapsed = System.currentTimeMillis() - windowStart.get()
    if (elapsed >= 1000L) ratePerSecond
    else (ratePerSecond - tokensUsed.get()).max(0)
  }
}

// ── Status models ─────────────────────────────────────────────────────────────

/**
 * @param maxConcurrent              configured concurrency cap
 * @param availablePermits           unused concurrency slots right now
 * @param currentlyProcessing        in-flight requests right now
 * @param maxPerSecond               configured per-second rate cap (0 = unlimited)
 * @param tokensRemainingThisSecond  tokens still available this second (-1 = unlimited)
 */
case class ServiceThrottleState(
  maxConcurrent:             Int,
  availablePermits:          Int,
  currentlyProcessing:       Int,
  maxPerSecond:              Int,
  tokensRemainingThisSecond: Int
)

case class ThrottlingStatus(
  enrolmentStoreProxy: ServiceThrottleState
)

