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
import org.mockito.Mockito.{times, verify, when}
import play.api.mvc.AnyContentAsEmpty
import play.api.test.FakeRequest
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.UploadedDetails

import scala.concurrent.Future

class UpscanCallbackServiceSpec extends TestSupport with TestData:

  trait Setup {
    implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest()
    val mockUploadProgressTracker: UploadProgressTracker = mock[UploadProgressTracker]
    val callbackService: UpscanCallbackService = new UpscanCallbackService(mockUploadProgressTracker)
  }

  val uploadDetailsWithTimestamp: UploadedDetails = failedUploadedDetails.copy(
    details = Some(successfulUploadedDetails),
    uploadedDateTime = Some(createdAt)
  )

  "UpscanCallbackService" should {
    "handle ready callback correctly" in new Setup {
      when(mockUploadProgressTracker.registerUploadResult(any(), any())(any())).thenReturn(Future.unit)

      await(callbackService.handleCallback(readyCallbackBody))
      verify(mockUploadProgressTracker, times(1)).registerUploadResult(any(), any())(any())
    }

    "handle failed callback correctly" in new Setup {
      when(mockUploadProgressTracker.getUploadResult(any())).thenReturn(Future.successful(Some(uploadDetailsWithTimestamp)))
      when(mockUploadProgressTracker.registerUploadResult(any(), any())(any())).thenReturn(Future.unit)

      await(callbackService.handleCallback(failedCallbackBody))

      verify(mockUploadProgressTracker, times(1)).registerUploadResult(any(), any())(any())
    }

    "handles failed callback when no upload details can be found" in new Setup {
      when(mockUploadProgressTracker.getUploadResult(any())).thenReturn(Future.successful(None))
      when(mockUploadProgressTracker.registerUploadResult(any(), any())(any())).thenReturn(Future.unit)

      await(callbackService.handleCallback(failedCallbackBody))

      verify(mockUploadProgressTracker, times(1)).registerUploadResult(any(), any())(any())
    }
  }