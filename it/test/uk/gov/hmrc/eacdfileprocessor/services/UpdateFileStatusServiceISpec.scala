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
import org.mongodb.scala.model.{Filters, Updates}
import org.mongodb.scala.{Document, SingleObservableFuture}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, UnitSpec}
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.*
import uk.gov.hmrc.eacdfileprocessor.repository.{DeEnrolmentWorkItemMongoRepository, LockingRepository}
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.{Failed, Succeeded}
import uk.gov.hmrc.mongo.workitem.WorkItemFields

import scala.concurrent.ExecutionContext

class UpdateFileStatusServiceISpec extends IntegrationSpec with TestData with UnitSpec with Eventually:
  val executionContext = ec
  val fileRepo = fileRepository
  val mockLockService = new LockService {
    override val lockingRepository: LockingRepository = lockingRepo
  }

  private val deEnrolmentWorkItemRepository = new DeEnrolmentWorkItemMongoRepository(mongoRepository, appConfig)

  private val updateFileStatusService = new UpdateFileStatusService {
    override val fileRepository = fileRepo
    override val workItemRepository = deEnrolmentWorkItemRepository
    override val lockService = mockLockService
    override implicit val ec: ExecutionContext = executionContext
  }

  override def beforeEach(): Unit = {
    await(fileRepository.collection.drop().headOption())
    await(fileRepository.ensureIndexes())
    await(deEnrolmentWorkItemRepository.collection.deleteMany(Filters.exists("_id")).toFuture())
    await(lockingRepo.collection.deleteMany(filter = Document()).toFuture())
  }

  "UpdateFileStatusService" must {
    "invoke" must {
      "update file status to PROCESSEDSUCCESSFULLY when all work items succeeded" in {
        val fileDetails = scannedUploadedDetails.copy(status = PROCESSING, totalEntryCount = Some(2))
        await(fileRepository.createFileRecord(fileDetails))
        val workItemsWithCorrectRef = deEnrolmentWorkItems.map(_.copy(reference = fileDetails.reference.value))
        await(deEnrolmentWorkItemRepository.saveRecordDetails(workItemsWithCorrectRef, fileDetails.reference.value))

        await(
          deEnrolmentWorkItemRepository.collection
            .updateMany(
              Filters.eq(s"${WorkItemFields.default.item}.reference", fileDetails.reference.value),
              Updates.set(WorkItemFields.default.status, Succeeded.name)
            )
            .toFuture()
        )

        await(updateFileStatusService.invoke)

        eventually {
          val updatedFile = await(fileRepository.findByReference(fileDetails.reference))
          updatedFile.get.status shouldBe PROCESSEDSUCCESSFULLY
          await(deEnrolmentWorkItemRepository.collection.countDocuments().toFuture()) shouldBe 0
        }
      }

      "update file status to PROCESSEDWITHERRORS when any work items failed" in {
        val fileDetails = scannedUploadedDetails.copy(status = PROCESSING, totalEntryCount = Some(2))
        await(fileRepository.createFileRecord(fileDetails))
        val workItemsWithCorrectRef = deEnrolmentWorkItems.map(_.copy(reference = fileDetails.reference.value))
        await(deEnrolmentWorkItemRepository.saveRecordDetails(workItemsWithCorrectRef, fileDetails.reference.value))

        await(
          deEnrolmentWorkItemRepository.collection
            .updateOne(
              Filters.eq(s"${WorkItemFields.default.item}.recordDetail", workItemsWithCorrectRef.head.recordDetail),
              Updates.set(WorkItemFields.default.status, Succeeded.name)
            )
            .toFuture()
        )

        await(
          deEnrolmentWorkItemRepository.collection
            .updateOne(
              Filters.eq(s"${WorkItemFields.default.item}.recordDetail", workItemsWithCorrectRef.last.recordDetail),
              Updates.set(WorkItemFields.default.status, Failed.name)
            )
            .toFuture()
        )

        await(updateFileStatusService.invoke)

        eventually {
          val updatedFile = await(fileRepository.findByReference(fileDetails.reference))
          updatedFile.get.status shouldBe PROCESSEDWITHERRORS
          await(deEnrolmentWorkItemRepository.collection.countDocuments().toFuture()) shouldBe 0
        }
      }

      "not update file status when work items are incomplete" in {
        val fileDetails = scannedUploadedDetails.copy(status = PROCESSING, totalEntryCount = Some(2))
        await(fileRepository.createFileRecord(fileDetails))
        val workItemsWithCorrectRef = deEnrolmentWorkItems.map(_.copy(reference = fileDetails.reference.value))
        await(deEnrolmentWorkItemRepository.saveRecordDetails(workItemsWithCorrectRef, fileDetails.reference.value))

        await(updateFileStatusService.invoke)

        val updatedFile = await(fileRepository.findByReference(fileDetails.reference))
        updatedFile.get.status shouldBe PROCESSING
        await(deEnrolmentWorkItemRepository.collection.countDocuments().toFuture()) shouldBe 2
      }

      "not update file status when there are no processing files" in {
        val fileDetails = scannedUploadedDetails.copy(status = SCANNED)
        await(fileRepository.createFileRecord(fileDetails))

        await(updateFileStatusService.invoke)

        val updatedFile = await(fileRepository.findByReference(fileDetails.reference))
        updatedFile.get.status shouldBe SCANNED
      }

    }
  }
