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

import org.mockito.Mockito.{mock, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.time.{Millis, Seconds, Span}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ThrottlingServiceSpec extends AnyWordSpec with Matchers with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(
    timeout  = Span(10, Seconds),
    interval = Span(50, Millis)
  )

  // ── helpers ──────────────────────────────────────────────────────────────────

  private def makeConfig(
    maxConcurrent: Int = 5,
    maxPerSecond:  Int = 0
  ): AppConfig = {
    val mockServicesConfig = mock(classOf[uk.gov.hmrc.play.bootstrap.config.ServicesConfig])
    when(mockServicesConfig.baseUrl("internal-auth")).thenReturn("http://localhost:8470")
    when(mockServicesConfig.baseUrl("enrolment-store-proxy")).thenReturn("http://localhost:9877")

    new AppConfig(
      play.api.Configuration.from(Map(
        "appName"                                                  -> "test-app",
        "time-to-live.time"                                       -> "3",
        "internal-auth.token"                                     -> "test-token",
        "throttle.enrolment-store-proxy.max-concurrent"           -> maxConcurrent,
        "throttle.enrolment-store-proxy.max-per-second"           -> maxPerSecond
      )),
      mockServicesConfig
    )
  }

  // ── Semaphore (concurrency) tests ─────────────────────────────────────────────

  "ThrottlingService (concurrency)" should {

    "allow multiple concurrent requests up to the limit" in {
      val service = new ThrottlingService(makeConfig(maxConcurrent = 3))
      val futures = (1 to 3).map(i => service.throttleEnrolmentStoreProxyCall(Future.successful(i)))
      Future.sequence(futures).futureValue.sorted shouldEqual List(1, 2, 3)
    }

    "process requests beyond the limit once earlier ones complete" in {
      val service = new ThrottlingService(makeConfig(maxConcurrent = 3))
      val futures = (1 to 5).map(i => service.throttleEnrolmentStoreProxyCall(Future.successful(i)))
      Future.sequence(futures).futureValue should have size 5
    }

    "expose correct max-concurrent in status" in {
      val service = new ThrottlingService(makeConfig(maxConcurrent = 3))
      service.getThrottlingStatus.enrolmentStoreProxy.maxConcurrent shouldEqual 3
    }

    "release permits after operation completes" in {
      val service = new ThrottlingService(makeConfig())
      service.throttleEnrolmentStoreProxyCall(Future.successful("done")).futureValue shouldEqual "done"
      service.getThrottlingStatus.enrolmentStoreProxy.availablePermits shouldEqual 5
    }

    "propagate exceptions from the wrapped operation" in {
      val service = new ThrottlingService(makeConfig())
      val result  = service.throttleEnrolmentStoreProxyCall(Future.failed(new RuntimeException("boom")))
      result.failed.futureValue.getMessage shouldEqual "boom"
    }
  }

  // ── Token Bucket (per-second rate) tests ─────────────────────────────────────

  "TokenBucket" should {

    "return -1 available tokens when rate limiting is disabled (0)" in {
      val bucket = new TokenBucket(0)
      bucket.availableTokens shouldEqual -1
    }

    "report full budget at the start of a fresh window" in {
      val bucket = new TokenBucket(10)
      bucket.availableTokens shouldEqual 10
    }

    "decrease available tokens after each acquire" in {
      val bucket = new TokenBucket(5)
      bucket.acquire("test")
      bucket.availableTokens shouldEqual 4
      bucket.acquire("test")
      bucket.availableTokens shouldEqual 3
    }

    "not block when rate is 0 (unlimited)" in {
      val bucket = new TokenBucket(0)
      val start  = System.currentTimeMillis()
      (1 to 100).foreach(_ => bucket.acquire("test"))
      (System.currentTimeMillis() - start) should be < 500L
    }

    "allow acquiring up to ratePerSecond tokens without blocking" in {
      val bucket = new TokenBucket(5)
      val start  = System.currentTimeMillis()
      (1 to 5).foreach(_ => bucket.acquire("test"))
      (System.currentTimeMillis() - start) should be < 500L
      bucket.availableTokens shouldEqual 0
    }

    "block and wait for the next window when budget is exhausted" in {
      val bucket = new TokenBucket(3)
      (1 to 3).foreach(_ => bucket.acquire("test"))
      bucket.availableTokens shouldEqual 0

      val start   = System.currentTimeMillis()
      bucket.acquire("test")
      val elapsed = System.currentTimeMillis() - start
      elapsed should be >= 900L
    }
  }

  // ── Combined (rate + concurrency) tests ──────────────────────────────────────

  "ThrottlingService (rate + concurrency combined)" should {

    "expose maxPerSecond and tokens in status" in {
      val service = new ThrottlingService(makeConfig(maxPerSecond = 10))
      service.getThrottlingStatus.enrolmentStoreProxy.maxPerSecond shouldEqual 10
    }

    "report -1 tokensRemainingThisSecond when rate limiting is disabled" in {
      val service = new ThrottlingService(makeConfig())
      service.getThrottlingStatus.enrolmentStoreProxy.tokensRemainingThisSecond shouldEqual -1
    }

    "report non-negative tokensRemainingThisSecond when rate limiting is enabled" in {
      val service = new ThrottlingService(makeConfig(maxPerSecond = 10))
      service.getThrottlingStatus.enrolmentStoreProxy.tokensRemainingThisSecond should be >= 0
    }

    "complete requests successfully when both limits are satisfied" in {
      val service = new ThrottlingService(makeConfig(maxPerSecond = 20))
      val futures = (1 to 5).map(i => service.throttleEnrolmentStoreProxyCall(Future.successful(i)))
      Future.sequence(futures).futureValue.sorted shouldEqual List(1, 2, 3, 4, 5)
    }
  }
}
