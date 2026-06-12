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

import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.models.{Reference, StatusDetailsModel}
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRepository, LockingRepository}

import scala.concurrent.{ExecutionContext, Future}

class UpdateFileStatusServiceSpec extends TestSupport with TestData with UnitSpec:

  private val executionContext = ec

  trait Setup {
    val mockFileRepository = mock[FileRepository]
    val mockWorkItemRepository: DeEnrolmentWorkItemRepository = mock[DeEnrolmentWorkItemRepository]
    val mockLockingRepository = mock[LockingRepository]

    when(mockLockingRepository.lockJob(any())).thenReturn(Future.successful(true))
    when(mockLockingRepository.releaseLock(any())).thenReturn(Future.successful(true))

    val updateFileStatusService = new UpdateFileStatusService {
      override val fileRepository = mockFileRepository
      override val workItemRepository = mockWorkItemRepository
      override val lockService = new LockService(mockLockingRepository)
      override implicit val ec: ExecutionContext = executionContext
    }
  }

  "UpdateFileStatusService" must {
    "invoke" must {
      "successfully acquire lock and update file statuses" in new Setup {
        val fileRef = "test-ref-lock"
        val processingFile = StatusDetailsModel(fileRef, "test@test.com", "PID123", "Test User", Some("test.csv"), PROCESSING.value, None)
        val uploadedDetails = scannedUploadedDetails.copy(reference = Reference(fileRef), status = PROCESSING, totalEntryCount = Some(2))

        when(mockFileRepository.findByStatus(PROCESSING)).thenReturn(Future.successful(Seq(processingFile)))
        when(mockWorkItemRepository.incompleteWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(0))
        when(mockWorkItemRepository.succeededWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(2))
        when(mockWorkItemRepository.failedWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(0))
        when(mockFileRepository.findByReference(Reference(fileRef))).thenReturn(Future.successful(Some(uploadedDetails)))
        when(mockFileRepository.updateStatus(any(), any())).thenReturn(Future.successful(Some(uploadedDetails)))
        when(mockWorkItemRepository.deleteWorkItemsByReference(fileRef)).thenReturn(Future.successful(()))

        val result = await(updateFileStatusService.invoke)

        result shouldBe a[Left[Unit, LockResponse]]
        verify(mockFileRepository, times(1)).updateStatus(Reference(fileRef), PROCESSEDSUCCESSFULLY)
      }
    }

    "updateCompletedFileStatuses" must {
      "update file status to PROCESSEDSUCCESSFULLY when all work items succeeded" in new Setup {
        val fileRef = "test-ref-1"
        val processingFile = StatusDetailsModel(fileRef, "test@test.com", "PID123", "Test User", Some("test.csv"), PROCESSING.value, None)
        val uploadedDetails = scannedUploadedDetails.copy(reference = Reference(fileRef), status = PROCESSING, totalEntryCount = Some(2))

        when(mockFileRepository.findByStatus(PROCESSING)).thenReturn(Future.successful(Seq(processingFile)))
        when(mockWorkItemRepository.incompleteWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(0))
        when(mockWorkItemRepository.succeededWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(2))
        when(mockWorkItemRepository.failedWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(0))
        when(mockFileRepository.findByReference(Reference(fileRef))).thenReturn(Future.successful(Some(uploadedDetails)))
        when(mockFileRepository.updateStatus(any(), any())).thenReturn(Future.successful(Some(uploadedDetails)))
        when(mockWorkItemRepository.deleteWorkItemsByReference(fileRef)).thenReturn(Future.successful(()))

        await(updateFileStatusService.updateCompletedFileStatuses)

        verify(mockFileRepository, times(1)).updateStatus(Reference(fileRef), PROCESSEDSUCCESSFULLY)
        verify(mockWorkItemRepository, times(1)).deleteWorkItemsByReference(fileRef)
      }

      "update file status to PROCESSEDWITHERRORS when any work items failed" in new Setup {
        val fileRef = "test-ref-2"
        val processingFile = StatusDetailsModel(fileRef, "test@test.com", "PID123", "Test User", Some("test.csv"), PROCESSING.value, None)
        val uploadedDetails = scannedUploadedDetails.copy(reference = Reference(fileRef), status = PROCESSING, totalEntryCount = Some(2))

        when(mockFileRepository.findByStatus(PROCESSING)).thenReturn(Future.successful(Seq(processingFile)))
        when(mockWorkItemRepository.incompleteWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(0))
        when(mockWorkItemRepository.succeededWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(1))
        when(mockWorkItemRepository.failedWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(1))
        when(mockFileRepository.findByReference(Reference(fileRef))).thenReturn(Future.successful(Some(uploadedDetails)))
        when(mockFileRepository.updateStatus(any(), any())).thenReturn(Future.successful(Some(uploadedDetails)))
        when(mockWorkItemRepository.deleteWorkItemsByReference(fileRef)).thenReturn(Future.successful(()))

        await(updateFileStatusService.updateCompletedFileStatuses)

        verify(mockFileRepository, times(1)).updateStatus(Reference(fileRef), PROCESSEDWITHERRORS)
        verify(mockWorkItemRepository, times(1)).deleteWorkItemsByReference(fileRef)
      }

      "not update file status when work items are still incomplete" in new Setup {
        val fileRef = "test-ref-3"
        val processingFile = StatusDetailsModel(fileRef, "test@test.com", "PID123", "Test User", Some("test.csv"), PROCESSING.value, None)

        when(mockFileRepository.findByStatus(PROCESSING)).thenReturn(Future.successful(Seq(processingFile)))
        when(mockWorkItemRepository.incompleteWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(2))

        await(updateFileStatusService.updateCompletedFileStatuses)

        verify(mockFileRepository, times(0)).updateStatus(any(), any())
        verify(mockWorkItemRepository, times(0)).deleteWorkItemsByReference(any())
      }

      "not process any files when there are no processing files" in new Setup {
        when(mockFileRepository.findByStatus(PROCESSING)).thenReturn(Future.successful(Seq.empty))

        await(updateFileStatusService.updateCompletedFileStatuses)

        verify(mockWorkItemRepository, times(0)).incompleteWorkItemsCountForRef(any())
        verify(mockFileRepository, times(0)).updateStatus(any(), any())
      }

      "handle reconciliation error when counts don't match totalEntryCount" in new Setup {
        val fileRef = "test-ref-4"
        val processingFile = StatusDetailsModel(fileRef, "test@test.com", "PID123", "Test User", Some("test.csv"), PROCESSING.value, None)
        val uploadedDetails = scannedUploadedDetails.copy(reference = Reference(fileRef), status = PROCESSING, totalEntryCount = Some(5))

        when(mockFileRepository.findByStatus(PROCESSING)).thenReturn(Future.successful(Seq(processingFile)))
        when(mockWorkItemRepository.incompleteWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(0))
        when(mockWorkItemRepository.succeededWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(2))
        when(mockWorkItemRepository.failedWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(1))
        when(mockFileRepository.findByReference(Reference(fileRef))).thenReturn(Future.successful(Some(uploadedDetails)))
        when(mockFileRepository.updateStatus(any(), any())).thenReturn(Future.successful(Some(uploadedDetails)))
        when(mockWorkItemRepository.deleteWorkItemsByReference(fileRef)).thenReturn(Future.successful(()))

        await(updateFileStatusService.updateCompletedFileStatuses)

        verify(mockFileRepository, times(1)).updateStatus(Reference(fileRef), PROCESSEDWITHERRORS)
        verify(mockWorkItemRepository, times(1)).deleteWorkItemsByReference(fileRef)
      }

      "handle multiple processing files correctly" in new Setup {
        val fileRef1 = "test-ref-5"
        val fileRef2 = "test-ref-6"
        val processingFile1 = StatusDetailsModel(fileRef1, "test@test.com", "PID123", "Test User", Some("test1.csv"), PROCESSING.value, None)
        val processingFile2 = StatusDetailsModel(fileRef2, "test@test.com", "PID456", "Test User 2", Some("test2.csv"), PROCESSING.value, None)
        val uploadedDetails1 = scannedUploadedDetails.copy(reference = Reference(fileRef1), status = PROCESSING, totalEntryCount = Some(2))
        val uploadedDetails2 = scannedUploadedDetails.copy(reference = Reference(fileRef2), status = PROCESSING, totalEntryCount = Some(3))

        when(mockFileRepository.findByStatus(PROCESSING)).thenReturn(Future.successful(Seq(processingFile1, processingFile2)))

        when(mockWorkItemRepository.incompleteWorkItemsCountForRef(fileRef1)).thenReturn(Future.successful(0))
        when(mockWorkItemRepository.succeededWorkItemsCountForRef(fileRef1)).thenReturn(Future.successful(2))
        when(mockWorkItemRepository.failedWorkItemsCountForRef(fileRef1)).thenReturn(Future.successful(0))
        when(mockFileRepository.findByReference(Reference(fileRef1))).thenReturn(Future.successful(Some(uploadedDetails1)))

        when(mockWorkItemRepository.incompleteWorkItemsCountForRef(fileRef2)).thenReturn(Future.successful(1))

        when(mockFileRepository.updateStatus(any(), any())).thenReturn(Future.successful(Some(uploadedDetails1)))
        when(mockWorkItemRepository.deleteWorkItemsByReference(fileRef1)).thenReturn(Future.successful(()))

        await(updateFileStatusService.updateCompletedFileStatuses)

        verify(mockFileRepository, times(1)).updateStatus(Reference(fileRef1), PROCESSEDSUCCESSFULLY)
        verify(mockWorkItemRepository, times(1)).deleteWorkItemsByReference(fileRef1)
        verify(mockFileRepository, times(0)).updateStatus(eqTo(Reference(fileRef2)), any())
        verify(mockWorkItemRepository, times(0)).deleteWorkItemsByReference(fileRef2)
      }

      "handle file not found gracefully" in new Setup {
        val fileRef = "test-ref-7"
        val processingFile = StatusDetailsModel(fileRef, "test@test.com", "PID123", "Test User", Some("test.csv"), PROCESSING.value, None)

        when(mockFileRepository.findByStatus(PROCESSING)).thenReturn(Future.successful(Seq(processingFile)))
        when(mockWorkItemRepository.incompleteWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(0))
        when(mockWorkItemRepository.succeededWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(2))
        when(mockWorkItemRepository.failedWorkItemsCountForRef(fileRef)).thenReturn(Future.successful(0))
        when(mockFileRepository.findByReference(Reference(fileRef))).thenReturn(Future.successful(None))

        await(updateFileStatusService.updateCompletedFileStatuses)

        verify(mockFileRepository, times(0)).updateStatus(any(), any())
        verify(mockWorkItemRepository, times(0)).deleteWorkItemsByReference(any())
      }
    }
  }
