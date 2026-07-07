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

import org.mockito.ArgumentMatchers.{any, startsWith}
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.{BAD_GATEWAY, BAD_REQUEST, NO_CONTENT, OK}
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.Future

class EspConnectorSpec extends TestSupport {

  private given HeaderCarrier = HeaderCarrier()

  val servicesConfig: ServicesConfig = mock[ServicesConfig]
  val appConfig: AppConfig = mock[AppConfig]
  val httpClient: HttpClientV2 = mock[HttpClientV2]
  val requestBuilder: RequestBuilder = mock[RequestBuilder]

  "EspConnector" should {
    "calling ES1 display correct behavior" when {

      "ES1 returns 204" in {
        when(servicesConfig.baseUrl("enrolment-store-proxy")).thenReturn("http://localhost:7775")
        when(httpClient.get(any())(any())).thenReturn(requestBuilder)
        when(requestBuilder.execute(any[HttpReads[HttpResponse]], any()))
          .thenReturn(Future.successful(HttpResponse(NO_CONTENT)))

        val connector = EspConnector(httpClient, appConfig, servicesConfig)

        await(connector.callES1("Test-enrolement-key", "princpal")).status shouldBe NO_CONTENT
      }

      "ES1 returns 400" in {

        when(servicesConfig.baseUrl("enrolment-store-proxy")).thenReturn("http://localhost:7775")
        when(httpClient.get(any())(any())).thenReturn(requestBuilder)
        when(requestBuilder.execute(any[HttpReads[HttpResponse]], any()))
          .thenReturn(Future.successful(HttpResponse(BAD_REQUEST, body =
            """
              |{
              |    "code": "TYPE_PARAMETER_INVALID",
              |    "message": "The type parameter was invalid. Expected all, principal or delegated"
              |}
              |""".stripMargin)))

        val connector = EspConnector(httpClient, appConfig, servicesConfig)
        val result = await(connector.callES1("Test-enrolement-key", "princpal"))
        result.status shouldBe BAD_REQUEST
        result.body.contains("The type parameter was invalid. Expected all, principal or delegated") shouldBe true

      }

      "ES1 returns 200" in {

        when(servicesConfig.baseUrl("enrolment-store-proxy")).thenReturn("http://localhost:7775")
        when(httpClient.get(any())(any())).thenReturn(requestBuilder)
        when(requestBuilder.execute(any[HttpReads[HttpResponse]], any()))
          .thenReturn(Future.successful(HttpResponse(OK, body =
            """
              |{
              |    principalGroupIds: [
              |       "c0506dd9-1feb-400a-bf70-6351e1ff7510"
              |    ],
              |    delegatedGroupIds: [
              |       "c0506dd9-1feb-400a-bf70-6351e1ff7512",
              |       "c0506dd9-1feb-400a-bf70-6351e1ff7513"
              |    ]
              |}
              |""".stripMargin)))

        val connector = EspConnector(httpClient, appConfig, servicesConfig)

        await(connector.callES1("Test-enrolement-key", "princpal")).status shouldBe OK
      }
    }
    "calling ES9 display correct behavior" when {

      "ES9 returns 500" in {
        when(servicesConfig.baseUrl("enrolment-store-proxy")).thenReturn("http://localhost:7775")
        when(httpClient.delete(any())(any())).thenReturn(requestBuilder)
        when(requestBuilder.execute(any[HttpReads[HttpResponse]], any()))
          .thenReturn(Future.successful(HttpResponse(BAD_GATEWAY, body =
            """
              |{
              |    "code": "INTERNAL_SERVER_ERROR",
              |    "message": "An unexpected error occurred"
              |}
              |""".stripMargin)))

        val connector = EspConnector(httpClient, appConfig, servicesConfig)
        val result = await(connector.callES9("Test-group-id", "Test-enrolement-key"))
        result.status shouldBe BAD_GATEWAY
        result.body.contains("An unexpected error occurred") shouldBe true
      }
      "ES9 returns 204" in {
        when(servicesConfig.baseUrl("enrolment-store-proxy")).thenReturn("http://localhost:7775")
        when(httpClient.delete(any())(any())).thenReturn(requestBuilder)
        when(requestBuilder.execute(any[HttpReads[HttpResponse]], any()))
          .thenReturn(Future.successful(HttpResponse(NO_CONTENT)))

        val connector = EspConnector(httpClient, appConfig, servicesConfig)

        await(connector.callES9("Test-group-id", "Test-enrolement-key")).status shouldBe NO_CONTENT
      }
      "ES9 returns 400" in {
        when(servicesConfig.baseUrl("enrolment-store-proxy")).thenReturn("http://localhost:7775")
        when(httpClient.delete(any())(any())).thenReturn(requestBuilder)
        when(requestBuilder.execute(any[HttpReads[HttpResponse]], any()))
          .thenReturn(Future.successful(HttpResponse(BAD_REQUEST, body =
            """
              |{
              |    "code": "ENROLMENT_NOT_FOUND",
              |    "message": "The enrolment was not found for the group"
              |}
              |""".stripMargin)))

        val connector = EspConnector(httpClient, appConfig, servicesConfig)
        val result = await(connector.callES9("Test-group-id", "Test-enrolement-key"))
        result.status shouldBe BAD_REQUEST
        result.body.contains("The enrolment was not found for the group") shouldBe true
      }
    }
  }
}