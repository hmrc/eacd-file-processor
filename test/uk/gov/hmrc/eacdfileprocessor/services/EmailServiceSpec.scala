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
import org.mockito.Mockito.{never, verify, when}
import org.scalactic.Prettifier.default
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.connectors.EmailConnector
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.APPROVED

import java.time.Instant
import java.time.Instant.now
import scala.concurrent.Future

class EmailServiceSpec extends TestSupport with TestData with UnitSpec:
  private val mockAppConfig = mock[AppConfig]
  trait SetUp() {
    val mockEmailConnector = mock[EmailConnector]
    val emailService = EmailService(appConfig = mockAppConfig)(mockEmailConnector)
    when(mockAppConfig.emailEnabled).thenReturn(true)
  }
  "EmailConnector" must {
    "sendFileFailEmail" must {
      "return true for sending file fail email successfully" in new SetUp {
        when(mockEmailConnector.sendEmail(any(), any(), any())(any(), any()))
          .thenReturn(Future.successful(true))

        val result = await(emailService.sendFileFailEmail(initiateUploadDetails.copy(uploadedDateTime = Some(now())), failedFileDetails))

        result shouldBe true
      }
      "throw exception when uploadedDateTime is missing" in new SetUp {
        val exception = intercept[RuntimeException] {
          await(emailService.sendFileFailEmail(initiateUploadDetails, failedFileDetails))
        }

        exception.getMessage contains "Uploaded date time not found for reference" shouldBe true
      }
      "sendFileFailEmail must not be sent when email is disabled" in new SetUp {
        when(mockAppConfig.emailEnabled).thenReturn(false)
        await(emailService.sendFileFailEmail(initiateUploadDetails.copy(uploadedDateTime = Some(now())), failedFileDetails))
        verify(mockEmailConnector, never()).sendEmail(any(), any(), any())(any(), any())
      }
    }
    "sendFileScannedEmail" must {
      "return true for sending file fail email successfully" in new SetUp {
        when(mockEmailConnector.sendEmail(any(), any(), any())(any(), any()))
          .thenReturn(Future.successful(true))

        val result = await(emailService.sendFileScannedEmail(initiateUploadDetails.copy(uploadedDateTime = Some(now())), successfulUploadedDetails, "60"))

        result shouldBe true
      }
      "throw exception when uploadedDateTime is missing" in new SetUp {
        val exception = intercept[RuntimeException] {
          await(emailService.sendFileFailEmail(initiateUploadDetails, failedFileDetails))
        }

        exception.getMessage contains "Uploaded date time not found for reference" shouldBe true
      }
      "sendFileScannedEmail must not be sent" in new SetUp {
        when(mockAppConfig.emailEnabled).thenReturn(false)
        await(emailService.sendFileScannedEmail(initiateUploadDetails.copy(uploadedDateTime = Some(now())), successfulUploadedDetails, "60"))
        verify(mockEmailConnector, never()).sendEmail(any(), any(), any())(any(), any())
      }
    }
    "sendUpdateFileStatusEmail" must {
      "return true for sending update status email successfully when status is not approved" in new SetUp {
        when(mockEmailConnector.sendEmail(any(), any(), any())(any(), any()))
          .thenReturn(Future.successful(true))

        val result = await(emailService.sendUpdateFileStatusEmail(initiateUploadDetails.copy(uploadedDateTime = Some(now()), approverDetails = Some(approverDetails))))

        result shouldBe true
      }
      "return true for sending update status email successfully when status is approved" in new SetUp {
        when(mockEmailConnector.sendEmail(any(), any(), any())(any(), any()))
          .thenReturn(Future.successful(true))

        val result = await(emailService.sendUpdateFileStatusEmail(initiateUploadDetails.copy(status = APPROVED, uploadedDateTime = Some(now()), approverDetails = Some(approverDetails))))

        result shouldBe true
      }
      "throw exception when approver details is missing" in new SetUp {
        val exception = intercept[RuntimeException] {
          await(emailService.sendUpdateFileStatusEmail(initiateUploadDetails))
        }

        exception.getMessage contains "Approver details not found for file reference" shouldBe true
      }
      "throw exception when uploadedDateTime is missing" in new SetUp {
        val exception = intercept[RuntimeException] {
          await(emailService.sendUpdateFileStatusEmail(initiateUploadDetails.copy(approverDetails = Some(approverDetails))))
        }

        exception.getMessage contains "Uploaded date time not found for reference" shouldBe true
      }
      "sendUpdateFileStatusEmail must not be sent when email is disabled" in new SetUp {
        when(mockAppConfig.emailEnabled).thenReturn(false)
        await(emailService.sendUpdateFileStatusEmail(initiateUploadDetails.copy(uploadedDateTime = Some(now()), approverDetails = Some(approverDetails))))
        verify(mockEmailConnector, never()).sendEmail(any(), any(), any())(any(), any())
      }
    }
    "sendFileAutoDeletedEmail" must {
      "return true for sending file auto deleted email successfully" in new SetUp {
        when(mockEmailConnector.sendEmail(any(), any(), any())(any(), any()))
          .thenReturn(Future.successful(true))

        val result = await(emailService.sendFileAutoDeletedEmail(initiateUploadDetails.copy(uploadedDateTime = Some(now()), details = Some(successfulUploadedDetails)), "60"))

        result shouldBe true
      }
      "throw exception when uploadedDateTime is missing" in new SetUp {
        val exception = intercept[RuntimeException] {
          await(emailService.sendFileAutoDeletedEmail(initiateUploadDetails.copy(details = Some(successfulUploadedDetails)), "60"))
        }

        exception.getMessage contains "Uploaded date time not found for reference" shouldBe true
      }
      "throw exception when file name is missing" in new SetUp {
        val exception = intercept[RuntimeException] {
          await(emailService.sendFileAutoDeletedEmail(initiateUploadDetails.copy(details = Some(failedFileDetails)), "60"))
        }

        exception.getMessage contains "File name is missing for reference" shouldBe true
      }
      "sendFileAutoDeletedEmail must not be sent when email is disabled" in new SetUp {
        when(mockAppConfig.emailEnabled).thenReturn(false)
        await(emailService.sendFileAutoDeletedEmail(initiateUploadDetails.copy(details = Some(successfulUploadedDetails)), "60"))
        verify(mockEmailConnector, never()).sendEmail(any(), any(), any())(any(), any())
      }
    }
    "sendFileProcessedEmail" must {
      "return true for sending file processed email successfully" in new SetUp {
        when(mockEmailConnector.sendEmail(any(), any(), any())(any(), any()))
          .thenReturn(Future.successful(true))

        val result = await(emailService.sendFileProcessedEmail(initiateUploadDetails.copy(uploadedDateTime = Some(now()), details = Some(successfulUploadedDetails))))

        result shouldBe true
      }
      "throw exception when uploadedDateTime is missing" in new SetUp {
        val exception = intercept[RuntimeException] {
          await(emailService.sendFileProcessedEmail(initiateUploadDetails.copy(details = Some(successfulUploadedDetails))))
        }

        exception.getMessage contains "Uploaded date time not found for reference" shouldBe true
      }
      "throw exception when file name is missing" in new SetUp {
        val exception = intercept[RuntimeException] {
          await(emailService.sendFileProcessedEmail(initiateUploadDetails.copy(details = Some(failedFileDetails))))
        }

        exception.getMessage contains "File name is missing for reference" shouldBe true
      }
      "sendFileProcessedEmail must not be sent when email is disabled" in new SetUp {
        when(mockAppConfig.emailEnabled).thenReturn(false)
        await(emailService.sendFileProcessedEmail(initiateUploadDetails.copy(details = Some(failedFileDetails))))
        verify(mockEmailConnector, never()).sendEmail(any(), any(), any())(any(), any())
      }
    }
    "formatDateTime" must {
      "return correct format in 24 hours" in new SetUp {
        val time = Instant.parse("2026-07-30T14:13:53.726Z")
        val actual = emailService.formatDateTime(time)
        actual shouldBe "30-07-2026 15:13:53"
      }
      "return correct format" in new SetUp {
        val time = Instant.parse("2026-07-30T09:30:15.726Z")
        val actual = emailService.formatDateTime(time)
        actual shouldBe "30-07-2026 10:30:15"
      }
    }
  }

