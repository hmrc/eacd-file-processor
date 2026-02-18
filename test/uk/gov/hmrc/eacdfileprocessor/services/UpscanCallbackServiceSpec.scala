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
import org.mockito.Mockito.verify
import org.scalatest.matchers.should.Matchers.shouldBe
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.http.BadRequestException

class UpscanCallbackServiceSpec extends TestSupport with TestData:
  val mockUploadProgressTracker: UploadProgressTracker = mock[UploadProgressTracker]
  val callbackService: UpscanCallbackService = new UpscanCallbackService(mockUploadProgressTracker)

  "UpscanCallbackService" should {
    "handle ready callback correctly" in {
      callbackService.handleCallback(readyCallbackBody)
      verify(mockUploadProgressTracker).registerUploadResult(any(), any())
    }

    "handle failed callback correctly" in {
      callbackService.handleCallback(failedCallbackBody)
      verify(mockUploadProgressTracker).registerUploadResult(any(), any())
    }

    "throws exception when MIME doesn't match" in {
      val exception = intercept[BadRequestException] {
        callbackService.handleCallback(wrongMIMEReadyCallbackBody)
      }

      exception.getMessage shouldBe "Incorrect file type uploaded, preferred file type was: image/png"
    }
  }