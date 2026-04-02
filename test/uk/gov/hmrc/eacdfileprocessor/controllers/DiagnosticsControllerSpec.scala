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

package uk.gov.hmrc.eacdfileprocessor.controllers

import org.mockito.Mockito.{mock, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.time.{Millis, Seconds, Span}
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.services.ThrottlingService

import scala.concurrent.ExecutionContext.Implicits.global

class DiagnosticsControllerSpec extends AnyWordSpec with Matchers with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(
    timeout  = Span(5, Seconds),
    interval = Span(50, Millis)
  )

  private val mockServicesConfig = mock(classOf[uk.gov.hmrc.play.bootstrap.config.ServicesConfig])
  when(mockServicesConfig.baseUrl("internal-auth")).thenReturn("http://localhost:8470")
  when(mockServicesConfig.baseUrl("enrolment-store-proxy")).thenReturn("http://localhost:9877")

  private val mockAppConfig = new AppConfig(
    play.api.Configuration.from(
      Map(
        "appName"                                        -> "test-app",
        "time-to-live.time"                             -> "3",
        "internal-auth.token"                           -> "test-token",
        "throttle.enrolment-store-proxy.max-concurrent" -> 5,
        "throttle.enrolment-store-proxy.max-per-second" -> 0
      )
    ),
    mockServicesConfig
  )

  "DiagnosticsController" should {

    "return throttling status as JSON with enrolment-store-proxy fields" in {
      val throttlingService = new ThrottlingService(mockAppConfig)
      val controller        = new DiagnosticsController(throttlingService, stubControllerComponents())

      val result     = controller.throttlingStatus()(FakeRequest())
      val bodyAsJson = contentAsJson(result)

      // Verify response structure
      (bodyAsJson \ "enrolmentStoreProxy" \ "maxConcurrent").as[Int]     shouldEqual 5
      (bodyAsJson \ "enrolmentStoreProxy" \ "availablePermits").as[Int]  shouldEqual 5
      (bodyAsJson \ "enrolmentStoreProxy" \ "currentlyProcessing").as[Int] shouldEqual 0
    }

    "return 200 OK status" in {
      val throttlingService = new ThrottlingService(mockAppConfig)
      val controller        = new DiagnosticsController(throttlingService, stubControllerComponents())

      val result = controller.throttlingStatus()(FakeRequest())
      status(result)      shouldEqual OK
      contentType(result) shouldEqual Some("application/json")
    }

    "return valid JSON" in {
      val throttlingService = new ThrottlingService(mockAppConfig)
      val controller        = new DiagnosticsController(throttlingService, stubControllerComponents())

      val bodyAsString = contentAsString(controller.throttlingStatus()(FakeRequest()))
      noException should be thrownBy { Json.parse(bodyAsString) }
    }

    "reflect consistent permit availability between calls" in {
      val throttlingService = new ThrottlingService(mockAppConfig)
      val controller        = new DiagnosticsController(throttlingService, stubControllerComponents())

      val permits1 = (contentAsJson(controller.throttlingStatus()(FakeRequest())) \ "enrolmentStoreProxy" \ "availablePermits").as[Int]
      val permits2 = (contentAsJson(controller.throttlingStatus()(FakeRequest())) \ "enrolmentStoreProxy" \ "availablePermits").as[Int]

      permits1 shouldEqual 5
      permits2 shouldEqual 5
    }
  }
}
