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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.Status.{BAD_REQUEST, OK}
import play.api.test.Helpers.POST
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.connectors.EnrolmentStoreProxyConnector
import uk.gov.hmrc.eacdfileprocessor.controllers.testonly.EnrolmentStoreProxyThrottlingController
import uk.gov.hmrc.eacdfileprocessor.helper.UnitSpec

import scala.concurrent.{ExecutionContext, Future}

class EnrolmentStoreProxyThrottlingControllerSpec extends UnitSpec {

  private implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext

  trait Setup {
    val connector: EnrolmentStoreProxyConnector = mock[EnrolmentStoreProxyConnector]
    val controller: EnrolmentStoreProxyThrottlingController =
      new EnrolmentStoreProxyThrottlingController(connector, Helpers.stubControllerComponents())
    when(connector.sendFileNotification(any())(any())).thenReturn(Future.unit)
  }

  "POST /test-only/throttle/enrolment-store-proxy/:count" should {

    "return 200 and trigger N connector calls" in new Setup {
      status(await(controller.fireBurst(5)(FakeRequest(POST, "/test-only/throttle/enrolment-store-proxy/5")))) shouldBe OK
      verify(connector, times(5)).sendFileNotification(any())(any())
    }

    "return 400 for non-positive count" in new Setup {
      status(await(controller.fireBurst(0)(FakeRequest(POST, "/test-only/throttle/enrolment-store-proxy/0")))) shouldBe BAD_REQUEST
      verify(connector, times(0)).sendFileNotification(any())(any())
    }

    "return 400 for count above maximum" in new Setup {
      status(await(controller.fireBurst(999)(FakeRequest(POST, "/test-only/throttle/enrolment-store-proxy/999")))) shouldBe BAD_REQUEST
      verify(connector, times(0)).sendFileNotification(any())(any())
    }
  }
}
