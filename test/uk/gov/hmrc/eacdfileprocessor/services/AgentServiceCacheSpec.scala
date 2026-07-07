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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, times, verify, when}
import org.scalatest.matchers.should.Matchers.shouldBe
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.connectors.Sec0Connector
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.http.HeaderCarrier

import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.Future

class AgentServiceCacheSpec extends TestSupport {

  private given HeaderCarrier = HeaderCarrier()

  val sec0Connector: Sec0Connector = mock[Sec0Connector]
  val appConfig: AppConfig = mock[AppConfig]
  var testClock: Clock = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    reset(sec0Connector, appConfig)
    testClock = Clock.fixed(Instant.parse("2026-06-16T12:00:00Z"), ZoneId.of("UTC"))
  }

  "AgentServiceCache" should {

    "fetch agent services from connector on first call" in {
      when(appConfig.sec0CacheRefreshHours).thenReturn(24)
      when(sec0Connector.getAgentServiceKeys()).thenReturn(Future.successful(Set("IR-SA", "VAT")))

      val cache = AgentServiceCache(sec0Connector, appConfig, testClock)

      val result = cache.getAgentServices()

      result.futureValue shouldBe Set("IR-SA", "VAT")
      verify(sec0Connector, times(1)).getAgentServiceKeys()
    }

    "return cached services on subsequent calls within refresh interval" in {
      when(appConfig.sec0CacheRefreshHours).thenReturn(24)
      when(sec0Connector.getAgentServiceKeys()).thenReturn(Future.successful(Set("IR-SA", "VAT")))

      val cache = AgentServiceCache(sec0Connector, appConfig, testClock)

      cache.getAgentServices().futureValue
      cache.getAgentServices().futureValue

      verify(sec0Connector, times(1)).getAgentServiceKeys()
    }

    "refresh services when cache expires" in {
      when(appConfig.sec0CacheRefreshHours).thenReturn(24)
      val firstCall = Set("IR-SA", "VAT")
      val secondCall = Set("IR-SA", "VAT", "ITSA")
      
      when(sec0Connector.getAgentServiceKeys())
        .thenReturn(Future.successful(firstCall))
        .thenReturn(Future.successful(secondCall))

      var currentTime = Instant.parse("2026-06-16T12:00:00Z")
      val advancingClock: Clock = new Clock {
        override def getZone: ZoneId = ZoneId.of("UTC")
        override def withZone(zone: ZoneId): Clock = this
        override def instant(): Instant = currentTime
      }

      val cache = AgentServiceCache(sec0Connector, appConfig, advancingClock)

      cache.getAgentServices().futureValue shouldBe firstCall

      currentTime = Instant.parse("2026-06-17T13:00:00Z")

      cache.getAgentServices().futureValue shouldBe secondCall

      verify(sec0Connector, times(2)).getAgentServiceKeys()
    }

    "propagate connector failures" in {
      when(appConfig.sec0CacheRefreshHours).thenReturn(24)
      when(sec0Connector.getAgentServiceKeys()).thenReturn(Future.failed(new RuntimeException("Service unavailable")))

      val cache = AgentServiceCache(sec0Connector, appConfig, testClock)

      val result = cache.getAgentServices()

      result.failed.futureValue.getMessage shouldBe "Service unavailable"
    }

    "handle empty service list from connector" in {
      when(appConfig.sec0CacheRefreshHours).thenReturn(24)
      when(sec0Connector.getAgentServiceKeys()).thenReturn(Future.successful(Set.empty))

      val cache = AgentServiceCache(sec0Connector, appConfig, testClock)

      val result = cache.getAgentServices()

      result.futureValue shouldBe Set.empty
      verify(sec0Connector, times(1)).getAgentServiceKeys()
    }

    "cache and reuse empty results until expiry" in {
      when(appConfig.sec0CacheRefreshHours).thenReturn(24)
      when(sec0Connector.getAgentServiceKeys()).thenReturn(Future.successful(Set.empty))

      val cache = AgentServiceCache(sec0Connector, appConfig, testClock)

      cache.getAgentServices().futureValue
      cache.getAgentServices().futureValue
      cache.getAgentServices().futureValue

      verify(sec0Connector, times(1)).getAgentServiceKeys()
    }
  }
}