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

import org.bson.types.ObjectId
import org.mockito.ArgumentMatchers.{any, eq as eqTo}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.models.{DeEnrolmentWorkItem, Details, FileRecordValidationError, FileStatus, Reference, UploadedDetails}
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRecordValidationErrorRepository, FileRepository, LockingRepository}
import uk.gov.hmrc.eacdfileprocessor.utils.FileWorkItemValidator
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}
import uk.gov.hmrc.http.HeaderCarrier

import java.net.URI
import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class FileWorkItemSchedulerServiceSpec extends AnyWordSpec with Matchers with MockitoSugar with ScalaFutures {

  private given ExecutionContext = ExecutionContext.global

  private val uploadedDetails = UploadedDetails(
    id = ObjectId.get(),
    reference = Reference("08aad019-7f66-4456-8d52-93f12109876f"),
    status = FileStatus.INITIAL,
    requestorPID = "12345678",
    requestorEmail = "test@hmrc.gov.uk",
    requestorName = "Test User",
    creationDateTime = Instant.now(),
    details = Some(Details.UploadedSuccessfully("abc.csv", "text/csv", URI("http://localhost/file").toURL, Some(10), "aa"))
  )

  trait Setup {
    val appConfig: AppConfig = mock[AppConfig]
    val deEnrolmentWorkItemRepository: DeEnrolmentWorkItemRepository = mock[DeEnrolmentWorkItemRepository]
    val fileRecordValidationErrorRepository: FileRecordValidationErrorRepository = mock[FileRecordValidationErrorRepository]
    val fileRepository: FileRepository = mock[FileRepository]
    val agentServiceCache: AgentServiceCache = new AgentServiceCache(
      sec0Connector = null,
      appConfig = null,
      clock = null
    ) {
      override def getAgentServices()(using HeaderCarrier): Future[Set[String]] =
        Future.successful(Set("HMRC-MTD-IT"))
    }
    val validator: FileWorkItemValidator = mock[FileWorkItemValidator]
    val lockRepository: LockingRepository = mock[LockingRepository]

    when(appConfig.fileWorkItemConcurrency).thenReturn(5)

    val lockService: LockService = new LockService(lockRepository) {
      override def lockAndRelease[T](job: String)(f: => Future[T])(using ExecutionContext): Future[Either[T, LockResponse]] =
        f.map(Left(_))
    }

    val service = new FileWorkItemSchedulerService(
      appConfig,
      deEnrolmentWorkItemRepository,
      fileRecordValidationErrorRepository,
      fileRepository,
      lockService,
      agentServiceCache,
      validator
    )

    val payload = DeEnrolmentWorkItem(
      reference = uploadedDetails.reference.value,
      recordDetail = "IR-SA~UTR~1234567890,principal",
      creationDateTime = Instant.now()
    )

    val workItem: WorkItem[DeEnrolmentWorkItem] = WorkItem(
      id = ObjectId.get(),
      receivedAt = Instant.now(),
      updatedAt = Instant.now(),
      availableAt = Instant.now(),
      status = ProcessingStatus.InProgress,
      failureCount = 0,
      item = payload
    )

    when(deEnrolmentWorkItemRepository.pullOutstandingBatch(5)).thenReturn(Future.successful(Seq(workItem)))
    when(deEnrolmentWorkItemRepository.markAsSucceeded(eqTo(workItem.id))).thenReturn(Future.successful(true))
    when(deEnrolmentWorkItemRepository.markAsFailed(eqTo(workItem.id))).thenReturn(Future.successful(true))
    when(deEnrolmentWorkItemRepository.countIncompleteByReference(payload.reference)).thenReturn(Future.successful(0L))
    when(deEnrolmentWorkItemRepository.hasAnyFailedByReference(payload.reference)).thenReturn(Future.successful(false))
    when(fileRepository.getNameOfFile(uploadedDetails.reference)).thenReturn(Future.successful(Some("abc.csv")))
    when(fileRepository.updateStatus(eqTo(uploadedDetails.reference), any[FileStatus])).thenReturn(Future.successful(Some(uploadedDetails)))
  }

  "FileWorkItemSchedulerService" should {
    "persist validation errors and increment total failure count for invalid rows" in new Setup {
      when(validator.validate(payload.recordDetail, Set("HMRC-MTD-IT"))).thenReturn(Some("Invalid action type"))
      when(fileRecordValidationErrorRepository.create(any[FileRecordValidationError])).thenReturn(Future.unit)
      when(fileRepository.incrementFailureCount(Reference(payload.reference))).thenReturn(Future.successful(Some(uploadedDetails)))
      when(deEnrolmentWorkItemRepository.hasAnyFailedByReference(payload.reference)).thenReturn(Future.successful(true))

      Await.result(service.invoke, 5.seconds)

      verify(fileRecordValidationErrorRepository).create(any[FileRecordValidationError])
      verify(fileRepository).incrementFailureCount(Reference(payload.reference))
      verify(deEnrolmentWorkItemRepository).markAsFailed(workItem.id)
      verify(deEnrolmentWorkItemRepository, never()).markAsSucceeded(any())
      verify(fileRepository).updateStatus(uploadedDetails.reference, FileStatus.FAILED)
    }

    "only mark work item as succeeded for valid rows and update file status to UPLOADED when all items complete" in new Setup {
      when(validator.validate(payload.recordDetail, Set("HMRC-MTD-IT"))).thenReturn(None)

      Await.result(service.invoke, 5.seconds)

      verify(fileRecordValidationErrorRepository, never()).create(any[FileRecordValidationError])
      verify(fileRepository, never()).incrementFailureCount(any())
      verify(deEnrolmentWorkItemRepository).markAsSucceeded(workItem.id)
      verify(fileRepository).updateStatus(uploadedDetails.reference, FileStatus.UPLOADED)
    }

    "not update file status when there are still incomplete work items" in new Setup {
      when(validator.validate(payload.recordDetail, Set("HMRC-MTD-IT"))).thenReturn(None)
      when(deEnrolmentWorkItemRepository.countIncompleteByReference(payload.reference)).thenReturn(Future.successful(3L))

      Await.result(service.invoke, 5.seconds)

      verify(deEnrolmentWorkItemRepository).markAsSucceeded(workItem.id)
      verify(fileRepository, never()).updateStatus(any[Reference], any[FileStatus])
    }

    "skip processing when lock is already held" in new Setup {
      override val lockService: LockService = new LockService(lockRepository) {
        override def lockAndRelease[T](job: String)(f: => Future[T])(using ExecutionContext): Future[Either[T, LockResponse]] =
          Future.successful(Right(MongoLocked))
      }

      override val service = new FileWorkItemSchedulerService(
        appConfig,
        deEnrolmentWorkItemRepository,
        fileRecordValidationErrorRepository,
        fileRepository,
        lockService,
        agentServiceCache,
        validator
      )

      Await.result(service.invoke, 5.seconds)

      verify(deEnrolmentWorkItemRepository, never()).pullOutstandingBatch(any[Int])
      verify(deEnrolmentWorkItemRepository, never()).markAsSucceeded(any())
      verify(deEnrolmentWorkItemRepository, never()).markAsFailed(any())
    }
  }
}

