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

import java.util.UUID
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.scalatest.concurrent.ScalaFutures
import play.api.libs.json.JsValue
import play.api.test.Helpers
import play.api.Configuration
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, UnitSpec}
import uk.gov.hmrc.http.client.{HttpClientV2, RequestBuilder}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, SessionId}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.language.postfixOps
import org.scalatest.matchers.should.Matchers.shouldBe


class EmailConnectorSpec extends TestData with UnitSpec with ScalaFutures {

  val sessionId: String = UUID.randomUUID.toString
  implicit val hc: HeaderCarrier = HeaderCarrier(sessionId = Some(SessionId(sessionId)))
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 5 seconds)
  val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext

  "EmailConnector" when {
    "sending an email reconfirmation" should {
      val mockHttp = mock(classOf[HttpClientV2])
      val mockRequestBuilder = mock(classOf[RequestBuilder])
      val runModeConfiguration = mock(classOf[Configuration])
      val mockServicesConfig = mock(classOf[ServicesConfig])

      val connector = new EmailConnectorImpl(mockHttp, runModeConfiguration, mockServicesConfig) {
        override lazy val serviceUrl: String = "http://test.com"
      }
      val requestorName = "Test User"
      val fileName = "test-file.csv"
      val uploadDateTime = java.time.Instant.now()
      val reference = "test-reference"
      val failureReason = "test-failure-reason"
      val failureMessage = "test-failure-message"
      val to = "joe.bloggs@gmail.com"
      val templateId = "dummyTemplateID"

      "return success for status 202 response" in {
        when(mockHttp.post(any())(any()))
          .thenReturn(mockRequestBuilder)

        when(mockRequestBuilder.withBody[JsValue](any())(any(), any(), any()))
          .thenReturn(mockRequestBuilder)

        when(mockRequestBuilder.execute[HttpResponse](any(), any()))
          .thenReturn(Future.successful(HttpResponse(202)))

        whenReady(
          connector.sendEmail(
            requestorName, fileName, uploadDateTime, to, reference, failureReason, failureMessage, templateId
          )(hc, ec)) { details =>
          details shouldBe true
        }
      }

      for(status <- List(200, 201, 400, 500, 501, 503)) {
        s"return failed if $status response received" in {
          when(mockHttp.post(any())(any()))
            .thenReturn(mockRequestBuilder)

          when(mockRequestBuilder.withBody[JsValue](any())(any(), any(), any()))
            .thenReturn(mockRequestBuilder)

          when(mockRequestBuilder.execute[HttpResponse](any(), any()))
            .thenReturn(Future.successful(HttpResponse(status)))

          whenReady(connector.sendEmail(
            requestorName, fileName, uploadDateTime, to, reference, failureReason, failureMessage, templateId
          )(hc, ec)) { details =>
            details shouldBe false
          }
        }
      }

      "return failed if an error is caught" in {
        when(mockHttp.post(any())(any()))
          .thenReturn(mockRequestBuilder)

        when(mockRequestBuilder.withBody[JsValue](any())(any(), any(), any()))
          .thenReturn(mockRequestBuilder)

        when(mockRequestBuilder.execute)
          .thenReturn(Future.failed(new Exception))

        whenReady(connector.sendEmail(
          requestorName, fileName, uploadDateTime, to, reference, failureReason, failureMessage, templateId
        )(hc, ec)) {
          _ shouldBe false
        }
      }

    }
  }
}
