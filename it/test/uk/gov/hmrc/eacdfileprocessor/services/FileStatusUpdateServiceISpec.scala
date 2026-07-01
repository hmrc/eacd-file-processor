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

import helper.IntegrationSpec
import org.bson.types.ObjectId
import org.mongodb.scala.ObservableFuture
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.scalatest.matchers.should.Matchers.{should, shouldBe}
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.TestData
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.models.{FileRecordValidationError, Reference}
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemMongoRepository, FileRecordValidationErrorRepository, FileRepository}
import uk.gov.hmrc.mongo.workitem.ProcessingStatus

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext

class FileStatusUpdateServiceISpec extends TestData with IntegrationSpec:

  private val mockAppConfig = mock[AppConfig]

  override lazy val fileRepository: FileRepository = app.injector.instanceOf[FileRepository]
  lazy val deEnrolmentWorkItemRepository: DeEnrolmentWorkItemMongoRepository = app.injector.instanceOf[DeEnrolmentWorkItemMongoRepository]
  lazy val fileRecordValidationErrorRepository: FileRecordValidationErrorRepository = app.injector.instanceOf[FileRecordValidationErrorRepository]
  lazy val lockService: LockService = app.injector.instanceOf[LockService]

  lazy val fileStatusUpdateService = new FileStatusUpdateService(
    appConfig = mockAppConfig,
    deEnrolmentWorkItemRepository = deEnrolmentWorkItemRepository,
    fileRecordValidationErrorRepository = fileRecordValidationErrorRepository,
    fileRepository = fileRepository,
    lockService = lockService
  )

  private def uniqueRef(prefix: String): Reference =
    Reference(s"$prefix-${UUID.randomUUID().toString}")

  override def beforeEach(): Unit = {
    await(fileRepository.collection.deleteMany(Filters.exists("_id")).toFuture())
    await(deEnrolmentWorkItemRepository.collection.deleteMany(Filters.exists("_id")).toFuture())
    await(fileRecordValidationErrorRepository.collection.drop().headOption())
    await(fileRecordValidationErrorRepository.ensureIndexes())
  }

  "FileStatusUpdateServiceISpec" should {

    "transition file from PROCESSING to PROCESSEDSUCCESSFULLY when all work items complete with no errors" in {
      val reference = uniqueRef("ref-ispec-success")
      val file = initiateUploadDetails.copy(
        reference = reference,
        status = PROCESSING,
        totalEntryCount = Some(3),
        totalSuccessCount = Some(3),
        totalFailureCount = Some(0)
      )

      await(fileRepository.createFileRecord(file))

      val workItems = Seq(
        deEnrolmentWorkItems.head.copy(reference = reference.value),
        deEnrolmentWorkItems.last.copy(reference = reference.value)
      )
      await(deEnrolmentWorkItemRepository.saveRecordDetails(workItems, reference.value))

      val pendingItems = await(deEnrolmentWorkItemRepository.findByReference(reference.value))
      pendingItems.foreach { workItem =>
        await(deEnrolmentWorkItemRepository.collection.updateOne(
          Filters.eq("_id", workItem.id),
          org.mongodb.scala.model.Updates.set("status", ProcessingStatus.Succeeded.name)
        ).toFuture())
      }

      val incompleteCount = await(deEnrolmentWorkItemRepository.countRemainingNonCompleteByReference(reference.value))
      incompleteCount shouldBe 0

      await(fileStatusUpdateService.invoke(using ExecutionContext.global))

      val updatedFile = await(fileRepository.findByReference(reference))
      updatedFile.value.status shouldBe PROCESSEDSUCCESSFULLY

      val remainingWorkItems = await(deEnrolmentWorkItemRepository.findByReference(reference.value))
      remainingWorkItems.size shouldBe 0
    }

    "transition file from PROCESSING to PROCESSEDWITHERRORS when validation errors exist" in {
      val reference = uniqueRef("ref-ispec-errors")
      await(fileRepository.collection.deleteMany(Filters.eq("reference.value", reference.value)).toFuture())
      await(fileRecordValidationErrorRepository.collection.deleteMany(Filters.eq("reference.value", reference.value)).toFuture()
      )

      val file = initiateUploadDetails.copy(
        reference = reference,
        status = PROCESSING,
        totalEntryCount = Some(2),
        totalSuccessCount = Some(1),
        totalFailureCount = Some(1)
      )

      await(fileRepository.createFileRecord(file))

      val workItems = Seq(
        deEnrolmentWorkItems.head.copy(reference = reference.value)
      )
      await(deEnrolmentWorkItemRepository.saveRecordDetails(workItems, reference.value))

      val pendingItems = await(deEnrolmentWorkItemRepository.findByReference(reference.value))
      pendingItems.foreach { workItem =>
        await(deEnrolmentWorkItemRepository.collection.updateOne(
          Filters.eq("_id", workItem.id),
          org.mongodb.scala.model.Updates.set("status", ProcessingStatus.Succeeded.name)
        ).toFuture())
      }

      val error = FileRecordValidationError(
        id = ObjectId.get(),
        reference = reference,
        fileName = "bulk.csv",
        recordDetail = "IR-SA-UTR-invalid,principal",
        errorMessage = "Invalid UTR format",
        creationDateTime = Instant.now()
      )
      await(fileRecordValidationErrorRepository.create(error))
      await(fileRecordValidationErrorRepository.countByReference(reference)) shouldBe 1

      await(fileStatusUpdateService.invoke(using ExecutionContext.global))

      val updatedFile = await(fileRepository.findByReference(reference))
      updatedFile.value.status shouldBe PROCESSEDWITHERRORS
    }

    "not transition file when work items still remain incomplete" in {
      val reference = uniqueRef("ref-ispec-pending")
      val file = initiateUploadDetails.copy(
        reference = reference,
        status = PROCESSING,
        totalEntryCount = Some(3),
        totalSuccessCount = Some(2),
        totalFailureCount = Some(0)
      )

      await(fileRepository.createFileRecord(file))

      val workItems = Seq(
        deEnrolmentWorkItems.head.copy(reference = reference.value),
        deEnrolmentWorkItems.last.copy(reference = reference.value)
      )
      await(deEnrolmentWorkItemRepository.saveRecordDetails(workItems, reference.value))

      val incompleteCount = await(deEnrolmentWorkItemRepository.countRemainingNonCompleteByReference(reference.value))
      incompleteCount should be > 0

      await(fileStatusUpdateService.invoke(using ExecutionContext.global))

      val unchangedFile = await(fileRepository.findByReference(reference))
      unchangedFile.value.status shouldBe PROCESSING
    }

    "handle reconciliation error when counts don't match" in {
      val reference = uniqueRef("ref-ispec-reconcile-error")
      val file = initiateUploadDetails.copy(
        reference = reference,
        status = PROCESSING,
        totalEntryCount = Some(10),
        totalSuccessCount = Some(5),
        totalFailureCount = Some(3)
      )

      await(fileRepository.createFileRecord(file))

      val workItems = Seq(
        deEnrolmentWorkItems.head.copy(reference = reference.value)
      )
      await(deEnrolmentWorkItemRepository.saveRecordDetails(workItems, reference.value))

      val pendingItems = await(deEnrolmentWorkItemRepository.findByReference(reference.value))
      pendingItems.foreach { workItem =>
        await(deEnrolmentWorkItemRepository.collection.updateOne(
          Filters.eq("_id", workItem.id),
          org.mongodb.scala.model.Updates.set("status", ProcessingStatus.Succeeded.name)
        ).toFuture())
      }

      await(fileStatusUpdateService.invoke(using ExecutionContext.global))

      val unchangedFile = await(fileRepository.findByReference(reference))
      unchangedFile.value.status shouldBe PROCESSING

      val remainingWorkItems = await(deEnrolmentWorkItemRepository.findByReference(reference.value))
      remainingWorkItems.size should be > 0
    }

    "process multiple files with different statuses independently" in {
      val runId = UUID.randomUUID().toString
      val ref1 = Reference(s"ref-ispec-multi-1-$runId")
      val ref2 = Reference(s"ref-ispec-multi-2-$runId")

      await(fileRepository.collection.deleteMany(Filters.in("reference.value", ref1.value, ref2.value)).toFuture())
      await(fileRecordValidationErrorRepository.collection.deleteMany(Filters.in("reference.value", ref1.value, ref2.value)).toFuture())

      val file1 = initiateUploadDetails.copy(
        id = ObjectId.get(),
        reference = ref1,
        status = PROCESSING,
        totalEntryCount = Some(2),
        totalSuccessCount = Some(2),
        totalFailureCount = Some(0),
        creationDateTime = Instant.now()
      )

      val file2 = initiateUploadDetails.copy(
        id = ObjectId.get(),
        reference = ref2,
        status = PROCESSING,
        totalEntryCount = Some(2),
        totalSuccessCount = Some(1),
        totalFailureCount = Some(1),
        creationDateTime = Instant.now()
      )

      await(fileRepository.createFileRecord(file1))
      await(fileRepository.createFileRecord(file2))

      val workItems1 = Seq(
        deEnrolmentWorkItems.head.copy(
          reference = ref1.value,
          recordDetail = s"IR-SA-UTR-${UUID.randomUUID().toString.take(12)},principal"
        )
      )

      val workItems2 = Seq(
        deEnrolmentWorkItems.head.copy(
          reference = ref2.value,
          recordDetail = s"IR-SA-UTR-${UUID.randomUUID().toString.take(12)},principal"
        )
      )

      await(deEnrolmentWorkItemRepository.saveRecordDetails(workItems1, ref1.value))
      await(deEnrolmentWorkItemRepository.saveRecordDetails(workItems2, ref2.value))

      val allPendingItems = await(deEnrolmentWorkItemRepository.collection.find().toFuture())
      allPendingItems.foreach { workItem =>
        await(deEnrolmentWorkItemRepository.collection.updateOne(
          Filters.eq("_id", workItem.id),
          org.mongodb.scala.model.Updates.set("status", ProcessingStatus.Succeeded.name)
        ).toFuture())
      }

      val error = FileRecordValidationError(
        id = ObjectId.get(),
        reference = ref2,
        fileName = "bulk.csv",
        recordDetail = "IR-SA-UTR-invalid,principal",
        errorMessage = "Invalid UTR format",
        creationDateTime = Instant.now()
      )
      await(fileRecordValidationErrorRepository.create(error))
      await(fileRecordValidationErrorRepository.countByReference(ref2)) shouldBe 1

      await(fileStatusUpdateService.invoke(using ExecutionContext.global))

      val updatedFile1 = await(fileRepository.findByReference(ref1))
      updatedFile1.value.status shouldBe PROCESSEDSUCCESSFULLY

      val updatedFile2 = await(fileRepository.findByReference(ref2))
      updatedFile2.value.status shouldBe PROCESSEDWITHERRORS

      val remainingWorkItems1 = await(deEnrolmentWorkItemRepository.findByReference(ref1.value))
      val remainingWorkItems2 = await(deEnrolmentWorkItemRepository.findByReference(ref2.value))
      remainingWorkItems1.size shouldBe 0
      remainingWorkItems2.size shouldBe 0
    }
  }

