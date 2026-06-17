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
import org.scalactic.Prettifier.default
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.connectors.EmailConnector
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}

import java.time.Instant.now
import scala.concurrent.Future

class EmailServiceSpec extends TestSupport with TestData with UnitSpec:
  private lazy val mockEmailConnector = mock[EmailConnector]
  private val emailService = EmailService(mockEmailConnector)

  "EmailConnector" must {
    "sendFileFailEmail" must {
      "return true for sending file fail email successfully" in {
        when(mockEmailConnector.sendFileFailedEmail(any(), any(), any(), any(), any(), any(), any(), any())(any(), any()))
          .thenReturn(Future.successful(true))

        val result = await(emailService.sendFileFailEmail(initiateUploadDetails.copy(uploadedDateTime = Some(now())), failedFileDetails))

        result shouldBe true
      }
      "throw exception when uploadedDateTime is missing" in {
        val exception = intercept[RuntimeException] {
          await(emailService.sendFileFailEmail(initiateUploadDetails, failedFileDetails))
        }

        exception.getMessage contains "Uploaded date time not found for reference" shouldBe true
      }
    }
    "sendUpdateFileStatusEmail" must {
      "return true for sending update status email successfully" in {
        when(mockEmailConnector.sendUpdateFileStatusEmail(any(), any(), any(), any(), any(), any(), any(), any())(any(), any()))
          .thenReturn(Future.successful(true))

        val result = await(emailService.sendUpdateFileStatusEmail(initiateUploadDetails.copy(uploadedDateTime = Some(now()), approverDetails = Some(approverDetails))))

        result shouldBe true
      }
      "throw exception when approver details is missing" in {
        val exception = intercept[RuntimeException] {
          await(emailService.sendUpdateFileStatusEmail(initiateUploadDetails))
        }

        exception.getMessage contains "Approver details not found for file reference" shouldBe true
      }
      "throw exception when uploadedDateTime is missing" in {
        val exception = intercept[RuntimeException] {
          await(emailService.sendUpdateFileStatusEmail(initiateUploadDetails.copy(approverDetails = Some(approverDetails))))
        }

        exception.getMessage contains "Uploaded date time not found for reference" shouldBe true
      }
    }
  }

