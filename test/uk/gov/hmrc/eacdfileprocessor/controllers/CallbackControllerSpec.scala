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
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.Status.{BAD_REQUEST, OK}
import play.api.test.Helpers.POST
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.services.UpscanCallbackService

import scala.concurrent.{ExecutionContext, Future}

class CallbackControllerSpec extends TestData with UnitSpec:
  private implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext

  trait Setup {
    val mockUpscanCallbackService = mock[UpscanCallbackService]
    val controllerComponents = Helpers.stubControllerComponents()
    val callbackController = new CallbackController(mockUpscanCallbackService, controllerComponents)
  }

  "POST /callback" should {
    "return 200 when passing successful response from upscan" in new Setup {
      when(mockUpscanCallbackService.handleCallback(any())).thenReturn(Future.unit)
      val request = FakeRequest(POST, "/callback").withBody(upscanSuccessResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe OK
    }
    "return 200 when passing failure response from upscan" in new Setup {
      when(mockUpscanCallbackService.handleCallback(any())).thenReturn(Future.unit)
      val request = FakeRequest(POST, "/callback").withBody(upscanFailureResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe OK
    }
    "return 400 when passing wrong file status response from upscan" in new Setup {
      when(mockUpscanCallbackService.handleCallback(any())).thenReturn(Future.unit)
      val request = FakeRequest(POST, "/callback").withBody(upscanWrongFileStatusResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe BAD_REQUEST
    }
  }
