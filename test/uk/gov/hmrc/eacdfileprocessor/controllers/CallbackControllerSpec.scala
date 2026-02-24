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
import org.scalatestplus.mockito.MockitoSugar.mock
import play.api.http.Status.NO_CONTENT
import play.api.test.Helpers.POST
import play.api.test.{FakeRequest, Helpers}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.services.UpscanCallbackService
import uk.gov.hmrc.http.BadRequestException

import scala.concurrent.{ExecutionContext, Future}

class CallbackControllerSpec extends TestData with UnitSpec:
  private implicit val ec: ExecutionContext = Helpers.stubControllerComponents().executionContext

  trait Setup {
    val mockUpscanCallbackService = mock[UpscanCallbackService]
    val controllerComponents = Helpers.stubControllerComponents()
    val callbackController = new CallbackController(mockUpscanCallbackService, controllerComponents)
  }

  "POST /callback" should {
    "return 204 when passing successful response from upscan" in new Setup {
      when(mockUpscanCallbackService.handleCallback(any())(any(), any())).thenReturn(Future.unit)
      val request = FakeRequest(POST, "/callback").withBody(upscanSuccessResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe NO_CONTENT
    }
    "return 204 when passing failure response from upscan" in new Setup {
      when(mockUpscanCallbackService.handleCallback(any())(any(), any())).thenReturn(Future.unit)
      val request = FakeRequest(POST, "/callback").withBody(upscanFailureResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe NO_CONTENT
    }
    "return 204 when CallbackService throw an exception" in new Setup {
      when(mockUpscanCallbackService.handleCallback(any())(any(), any())).thenReturn(Future(throw new BadRequestException(s"Incorrect file type uploaded, preferred file type was: text/csv")))
      val request = FakeRequest(POST, "/callback").withBody(upscanSuccessResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe NO_CONTENT
    }
    "return 204 when passing wrong file status response from upscan" in new Setup {
      val request = FakeRequest(POST, "/callback").withBody(upscanWrongFileStatusResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe NO_CONTENT
      verify(mockUpscanCallbackService, times(0)).handleCallback(any())(any(), any())
    }
    "return 204 when passing missing file status response from upscan" in new Setup {
      val request = FakeRequest(POST, "/callback").withBody(upscanMissingFileStatusResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe NO_CONTENT
      verify(mockUpscanCallbackService, times(0)).handleCallback(any())(any(), any())
    }
    "return 204 when missing reference on successful response from upscan" in new Setup {
      val request = FakeRequest(POST, "/callback").withBody(upscanMissingReferenceResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe NO_CONTENT
      verify(mockUpscanCallbackService, times(0)).handleCallback(any())(any(), any())
    }
    "return 204 when missing reference on failed response from upscan" in new Setup {
      val request = FakeRequest(POST, "/callback").withBody(upscanMissingReferenceFailureResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe NO_CONTENT
      verify(mockUpscanCallbackService, times(0)).handleCallback(any())(any(), any())
    }
    "return 204 when missing download url response from upscan" in new Setup {
      val request = FakeRequest(POST, "/callback").withBody(upscanMissingDownloadUrlResponse)
      val result = await(callbackController.callback()(request))
      status(result) shouldBe NO_CONTENT
      verify(mockUpscanCallbackService, times(0)).handleCallback(any())(any(), any())
    }
  }
