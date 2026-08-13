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

import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalactic.Prettifier.default
import org.scalatest.matchers.should.Matchers.shouldBe
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.models.{FileStatus, Reference}
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemMongoRepository, FileRecordValidationErrorRepository, FileRepository}

import scala.concurrent.Future

class FileStatusUpdateServiceSpec extends TestSupport with TestData with UnitSpec:
  implicit val mat: Materializer = mock[Materializer]
  private lazy val workItemRepository = mock[DeEnrolmentWorkItemMongoRepository]
  private lazy val validationErrorRepository = mock[FileRecordValidationErrorRepository]
  private lazy val lockService = mock[LockService]

  trait Setup {
    val repository = mock[FileRepository]
    val mockEmailService: EmailService = mock[EmailService]
    val fileStatusUpdateService = FileStatusUpdateService(workItemRepository, validationErrorRepository, repository,
      lockService, mockEmailService)
  }

  "FileStatusUpdateService" must {
    "processProcessingFiles" must {
      "transition file from PROCESSING to PROCESSEDSUCCESSFULLY when all work items complete with no errors" in new Setup {
        val file = initiateUploadDetails.copy(
          reference = Reference("ref1"),
          status = PROCESSING,
          totalEntryCount = Some(3),
          totalSuccessCount = Some(3),
          totalFailureCount = Some(0)
        )
        when(repository.findByStatusAsUploadedDetails(any())).thenReturn(Future.successful(Seq(file)))
        when(workItemRepository.countRemainingNonCompleteByReference(any())).thenReturn(Future.successful(0))
        when(validationErrorRepository.countByReference(any())).thenReturn(Future.successful(0))
        when(repository.updateStatus(any(), any())).thenReturn(Future.successful(Some(file)))

        await(fileStatusUpdateService.processProcessingFiles())
        verify(mockEmailService, org.mockito.Mockito.timeout(1000).times(1)).sendFileProcessedEmails(any())(any())
      }
      "transition file from PROCESSING to PROCESSEDWITHERRORS when validation errors exist" in new Setup {
        val file = initiateUploadDetails.copy(
          reference = Reference("ref1"),
          status = PROCESSING,
          totalEntryCount = Some(3),
          totalSuccessCount = Some(2),
          totalFailureCount = Some(1)
        )
        when(repository.findByStatusAsUploadedDetails(any())).thenReturn(Future.successful(Seq(file)))
        when(workItemRepository.countRemainingNonCompleteByReference(any())).thenReturn(Future.successful(0))
        when(validationErrorRepository.countByReference(any())).thenReturn(Future.successful(1))
        when(repository.updateStatus(any(), any())).thenReturn(Future.successful(Some(file)))

        await(fileStatusUpdateService.processProcessingFiles())
        verify(mockEmailService, org.mockito.Mockito.timeout(1000).times(1)).sendFileProcessedEmails(any())(any())
      }
      "not transition file when work items still remain incomplete" in new Setup {
        val file = initiateUploadDetails.copy(
          reference = Reference("ref1"),
          status = PROCESSING,
          totalEntryCount = Some(3)
        )
        when(repository.findByStatusAsUploadedDetails(any())).thenReturn(Future.successful(Seq(file)))
        when(workItemRepository.countRemainingNonCompleteByReference(any())).thenReturn(Future.successful(3))

        await(fileStatusUpdateService.processProcessingFiles())
        verify(repository, times(0)).updateStatus(any(), any())
        verify(mockEmailService, times(0)).sendFileProcessedEmails(any())(any())
      }
      "handle reconciliation error when counts don't match" in new Setup {
        val file = initiateUploadDetails.copy(
          reference = Reference("ref1"),
          status = PROCESSING,
          totalEntryCount = Some(10),
          totalSuccessCount = Some(5),
          totalFailureCount = Some(3)
        )
        when(repository.findByStatusAsUploadedDetails(any())).thenReturn(Future.successful(Seq(file)))
        when(repository.updateStatus(any(), any())).thenReturn(Future.successful(Some(file)))
        when(workItemRepository.countRemainingNonCompleteByReference(any())).thenReturn(Future.successful(0))

        await(fileStatusUpdateService.processProcessingFiles())
        verify(repository, times(1)).updateStatus(any(), any())
        verify(mockEmailService, times(0)).sendFileProcessedEmails(any())(any())
      }
      "throw exception when fail to update status" in new Setup {
        val file = initiateUploadDetails.copy(
          reference = Reference("ref1"),
          status = PROCESSING,
          totalEntryCount = Some(3),
          totalSuccessCount = Some(3),
          totalFailureCount = Some(0)
        )
        when(repository.findByStatusAsUploadedDetails(any())).thenReturn(Future.successful(Seq(file)))
        when(workItemRepository.countRemainingNonCompleteByReference(any())).thenReturn(Future.successful(0))
        when(validationErrorRepository.countByReference(any())).thenReturn(Future.successful(0))
        when(repository.updateStatus(any(), any())).thenReturn(Future.successful(None))

        val exception = intercept[RuntimeException] {
          await(fileStatusUpdateService.processProcessingFiles())
        }

        exception.getMessage contains "Failed to update file status for reference" shouldBe true
        verify(mockEmailService, times(0)).sendFileProcessedEmails(any())(any())
      }
    }
  }

