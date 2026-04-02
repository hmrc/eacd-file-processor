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
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

/**
 * Integration-level throttling tests for [[EnrolmentStoreProxyConnector]].
 *
 * These tests prove that the throttle gates inside [[ThrottlingService]] work correctly
 * when exercised through the connector — i.e. at the boundary where outbound calls to
 * enrolment-store-proxy are actually made.
 *
 * This is the ONLY integration spec that tests throttling behaviour in this service.
 */
class EnrolmentStoreProxyThrottlingISpec extends TestSupport with TestData {

  private def buildConnector(
    maxConcurrent: Int,
    maxPerSecond:  Int
  ): (EnrolmentStoreProxyConnector, RequestBuilder) = {
    val mockAppConfig = mock[AppConfig]
    when(mockAppConfig.maxConcurrentEnrolmentStoreProxyRequests).thenReturn(maxConcurrent)
    when(mockAppConfig.maxPerSecondEnrolmentStoreProxyRequests).thenReturn(maxPerSecond)
    when(mockAppConfig.enrolmentStoreProxyBaseUrl).thenReturn("http://localhost:9867")

    val mockHttpClient    = mock[HttpClientV2]
    val mockRequestBuilder = mock[RequestBuilder]

    when(mockHttpClient.get(any())(any())).thenReturn(mockRequestBuilder)
    when(mockRequestBuilder.execute(any[HttpReads[HttpResponse]], any()))
      .thenReturn(Future.successful(HttpResponse(200, body = "")))

    val throttlingService = new ThrottlingService(mockAppConfig)
    val connector         = new EnrolmentStoreProxyConnector(mockHttpClient, mockAppConfig, throttlingService)

    (connector, mockRequestBuilder)
  }

  "EnrolmentStoreProxyConnector throttling" should {

    "enforce max-concurrent: no more than N requests in-flight simultaneously" in {
      val maxConcurrent = 1
      val mockAppConfig = mock[AppConfig]
      when(mockAppConfig.maxConcurrentEnrolmentStoreProxyRequests).thenReturn(maxConcurrent)
      when(mockAppConfig.maxPerSecondEnrolmentStoreProxyRequests).thenReturn(0)
      when(mockAppConfig.enrolmentStoreProxyBaseUrl).thenReturn("http://localhost:9867")

      val mockHttpClient     = mock[HttpClientV2]
      val mockRequestBuilder = mock[RequestBuilder]
      when(mockHttpClient.get(any())(any())).thenReturn(mockRequestBuilder)

      val inFlight    = new AtomicInteger(0)
      val maxInFlight = new AtomicInteger(0)

      when(mockRequestBuilder.execute(any[HttpReads[HttpResponse]], any()))
        .thenAnswer((_: InvocationOnMock) => Future {
          val current = inFlight.incrementAndGet()
          maxInFlight.accumulateAndGet(current, Math.max)
          Thread.sleep(200)
          inFlight.decrementAndGet()
          HttpResponse(200, body = "")
        })

      val throttlingService = new ThrottlingService(mockAppConfig)
      val connector         = new EnrolmentStoreProxyConnector(mockHttpClient, mockAppConfig, throttlingService)

      implicit val hc: HeaderCarrier = HeaderCarrier()

      val start = System.currentTimeMillis()
      await(Future.sequence((1 to 3).map(i => connector.sendFileNotification(s"ref-$i"))))
      val elapsed = System.currentTimeMillis() - start

      maxInFlight.get() mustBe 1       // concurrency gate held
      elapsed must be >= 500L          // 3 sequential 200ms calls = at least 500ms
    }

    "enforce max-per-second: spread requests across second boundaries" in {
      val (connector, _) = buildConnector(maxConcurrent = 10, maxPerSecond = 1)

      implicit val hc: HeaderCarrier = HeaderCarrier()

      val start   = System.currentTimeMillis()
      await(Future.sequence((1 to 2).map(i => connector.sendFileNotification(s"ref-$i"))))
      val elapsed = System.currentTimeMillis() - start

      // 2 requests with max-per-second=1 means the second must wait ~1 second
      elapsed must be >= 900L
    }

    "complete all requests successfully when limits are not exceeded" in {
      val (connector, _) = buildConnector(maxConcurrent = 10, maxPerSecond = 0)

      implicit val hc: HeaderCarrier = HeaderCarrier()

      val results = await(Future.sequence((1 to 5).map(i => connector.sendFileNotification(s"ref-$i"))))
      results must have size 5
    }
  }
}
