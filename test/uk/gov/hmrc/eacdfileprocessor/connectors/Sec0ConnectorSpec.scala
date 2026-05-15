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

package uk.gov.hmrc.eacdfileprocessor.connectors

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.{BAD_GATEWAY, OK}
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}

import scala.concurrent.Future

class Sec0ConnectorSpec extends TestSupport {

  private given HeaderCarrier = HeaderCarrier()

  "Sec0Connector" should {

    "extract distinct non-empty services for successful responses" in {
      val appConfig = mock[AppConfig]
      val httpClient = mock[HttpClientV2]
      val requestBuilder = mock[RequestBuilder]

      when(appConfig.enrolmentStoreProxyBaseUrl).thenReturn("http://localhost:7775")
      when(appConfig.sec0AgentServicesPath).thenReturn("")
      when(httpClient.get(any())(any())).thenReturn(requestBuilder)
      when(requestBuilder.execute(any[HttpReads[HttpResponse]], any()))
        .thenReturn(Future.successful(HttpResponse(OK, body = """
            |{
            |  "enrolments": [
            |    {"service": " IR-SA "},
            |    {"service": "IR-SA"},
            |    {"service": " "},
            |    {"service": "VAT"}
            |  ]
            |}
            |""".stripMargin)))

      val connector = Sec0Connector(httpClient, appConfig)

      await(connector.getAgentServiceKeys()) shouldBe Set("IR-SA", "VAT")
    }

    "return empty set for non-2xx responses" in {
      val appConfig = mock[AppConfig]
      val httpClient = mock[HttpClientV2]
      val requestBuilder = mock[RequestBuilder]

      when(appConfig.enrolmentStoreProxyBaseUrl).thenReturn("http://localhost:7775")
      when(appConfig.sec0AgentServicesPath).thenReturn("")
      when(httpClient.get(any())(any())).thenReturn(requestBuilder)
      when(requestBuilder.execute(any[HttpReads[HttpResponse]], any()))
        .thenReturn(Future.successful(HttpResponse(BAD_GATEWAY, body = "{}")))

      val connector = Sec0Connector(httpClient, appConfig)

      await(connector.getAgentServiceKeys()) shouldBe Set.empty
    }
  }
}




