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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status.NO_CONTENT
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.connectors.EspConnector
import uk.gov.hmrc.eacdfileprocessor.models.{DeEnrolmentWorkItem, Details, FileRecordValidationError, FileStatus, Reference, UploadedDetails}
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemRepository, FileRecordValidationErrorRepository, FileRepository, LockingRepository}
import uk.gov.hmrc.eacdfileprocessor.utils.DeEnrolmentWorkItemValidator
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem}
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}

import java.net.URI
import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class DeEnrolmentWorkItemSchedulerServiceSpec extends AnyWordSpec with Matchers with MockitoSugar with ScalaFutures {

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
    val espConnector: EspConnector = mock[EspConnector]
    val agentServiceCache: AgentServiceCache = new AgentServiceCache(
      sec0Connector = null,
      appConfig = null,
      clock = null
    ) {
      override def getAgentServices()(using HeaderCarrier): Future[Set[String]] =
        Future.successful(Set("HMRC-MTD-IT"))
    }
    val validator: DeEnrolmentWorkItemValidator = mock[DeEnrolmentWorkItemValidator]
    val lockRepository: LockingRepository = mock[LockingRepository]

    when(appConfig.DeEnrolmentWorkItemConcurrency).thenReturn(5)

    val lockService: LockService = new LockService(lockRepository) {
      override def lockAndRelease[T](job: String)(f: => Future[T])(using ExecutionContext): Future[Either[T, LockResponse]] =
        f.map(Left(_))
    }

    val service = new DeEnrolmentWorkItemSchedulerService(
      appConfig,
      deEnrolmentWorkItemRepository,
      fileRecordValidationErrorRepository,
      fileRepository,
      espConnector,
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
    when(fileRepository.getNameOfFile(uploadedDetails.reference)).thenReturn(Future.successful(Some("abc.csv")))
  }

  "DeEnrolmentWorkItemSchedulerService" should {
    "persist validation errors and increment total failure count for invalid rows" in new Setup {
      when(validator.validate(payload.recordDetail, Set("HMRC-MTD-IT"))).thenReturn(Left("Invalid action type"))
      when(fileRecordValidationErrorRepository.create(any[FileRecordValidationError])).thenReturn(Future.unit)
      when(fileRepository.incrementFailureCount(Reference(payload.reference))).thenReturn(Future.successful(Some(uploadedDetails)))

      Await.result(service.invoke, 5.seconds)

      verify(fileRecordValidationErrorRepository).create(any[FileRecordValidationError])
      verify(fileRepository).incrementFailureCount(Reference(payload.reference))
    }

    "only mark work item as succeeded for valid rows" in new Setup {
      when(validator.validate(payload.recordDetail, Set("HMRC-MTD-IT")))
        .thenReturn(Right("IR-SA~UTR~1234567890", "principal"))
      when(espConnector.callES1(any[String], any[String])(using any[HeaderCarrier])).thenReturn(Future.successful(HttpResponse(NO_CONTENT, "")))
      when(fileRepository.incrementSuccessCount(Reference(payload.reference))).thenReturn(Future.successful(Some(uploadedDetails)))

      Await.result(service.invoke, 5.seconds)

      verify(fileRecordValidationErrorRepository, never()).create(any[FileRecordValidationError])
      verify(fileRepository, never()).incrementFailureCount(any())
    }

    "not call agentServiceCache when no work items are pulled" in new Setup {
      when(deEnrolmentWorkItemRepository.pullOutstandingBatch(5)).thenReturn(Future.successful(Seq.empty))

      var agentServicesCalled = false
      override val agentServiceCache: AgentServiceCache = new AgentServiceCache(
        sec0Connector = null,
        appConfig = null,
        clock = null
      ) {
        override def getAgentServices()(using HeaderCarrier): Future[Set[String]] = {
          agentServicesCalled = true
          Future.successful(Set("HMRC-MTD-IT"))
        }
      }

      override val service = new DeEnrolmentWorkItemSchedulerService(
        appConfig,
        deEnrolmentWorkItemRepository,
        fileRecordValidationErrorRepository,
        fileRepository,
        espConnector,
        lockService,
        agentServiceCache,
        validator
      )

      Await.result(service.invoke, 5.seconds)

      agentServicesCalled shouldBe false
      verify(fileRecordValidationErrorRepository, never()).create(any[FileRecordValidationError])
      verify(fileRepository, never()).incrementFailureCount(any())
    }

    "skip processing when lock is already held" in new Setup {
      override val lockService: LockService = new LockService(lockRepository) {
        override def lockAndRelease[T](job: String)(f: => Future[T])(using ExecutionContext): Future[Either[T, LockResponse]] =
          Future.successful(Right(MongoLocked))
      }

      override val service = new DeEnrolmentWorkItemSchedulerService(
        appConfig,
        deEnrolmentWorkItemRepository,
        fileRecordValidationErrorRepository,
        fileRepository,
        espConnector,
        lockService,
        agentServiceCache,
        validator
      )

      Await.result(service.invoke, 5.seconds)

      verify(deEnrolmentWorkItemRepository, never()).pullOutstandingBatch(any[Int])
    }
  }
}

