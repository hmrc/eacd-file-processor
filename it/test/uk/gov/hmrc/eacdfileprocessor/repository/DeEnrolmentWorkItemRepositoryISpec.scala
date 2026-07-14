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

package uk.gov.hmrc.eacdfileprocessor.repository

import helper.IntegrationSpec
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.{Filters, Updates}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.helper.TestData
import uk.gov.hmrc.eacdfileprocessor.models.DeEnrolmentWorkItem
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.mongo.workitem.{ProcessingStatus, WorkItem, WorkItemFields}

import java.time.Instant
import java.time.Instant.now
import java.time.temporal.ChronoUnit
import scala.language.postfixOps

class DeEnrolmentWorkItemRepositoryISpec extends TestData with IntegrationSpec with Eventually{
  private val repository = app.injector.instanceOf[DeEnrolmentWorkItemMongoRepository]

  override def beforeEach(): Unit = {
    await(repository.collection.deleteMany(Filters.exists("_id")).toFuture())
  }

  "repository" should {
    "update status of an item of work to Succeeded when successfully completed" in {
      val result: Seq[WorkItem[DeEnrolmentWorkItem]] = await(repository.saveRecordDetails(deEnrolmentWorkItems))
      result.size shouldBe 2
      result(0).status shouldBe ToDo
      result(1).status shouldBe ToDo
    }

    "delete work items by reference" in {
      await(repository.saveRecordDetails(deEnrolmentWorkItems))

      await(repository.deleteWorkItemsByReference("ref1"))
      val count = await(repository.collection.countDocuments().toFuture())
      count shouldBe 1
    }

    "return the count of incomplete work items" in {
      await(repository.saveRecordDetails(deEnrolmentWorkItems))
      await(repository.saveRecordDetails(deEnrolmentWorkItems))

      val count = await(repository.incompleteWorkItemsCountForRef("ref1"))
      count shouldBe 2
    }

    "return only incomplete statuses when one work" in {
      val allStatuses = ProcessingStatus.values.toSeq
      val incompleteStatuses = Set(ToDo, ProcessingStatus.InProgress)

      await(repository.saveRecordDetails(Seq(deEnrolmentWorkItems.last)))
      allStatuses.foreach { status =>
        val workItem = deEnrolmentWorkItems.head.copy(recordDetail = s"IR-SA-UTR-${status.name.toLowerCase},principal")
        await(repository.saveRecordDetails(Seq(workItem)))
        await(
          repository.collection
            .updateMany(
              Filters.eq(s"${WorkItemFields.default.item}.recordDetail", workItem.recordDetail),
              Updates.set(WorkItemFields.default.status, status.name)
            )
            .toFuture()
        )
      }

      val count = await(repository.incompleteWorkItemsCountForRef("ref1"))
      count shouldBe incompleteStatuses.size
    }

    "pull up to limit and not return already claimed items on subsequent calls" in {
      await(repository.saveRecordDetails(deEnrolmentWorkItems))

      val firstPull = await(repository.pullOutstandingBatch(1))
      firstPull.size shouldBe 1
      firstPull.head.item.reference shouldBe "ref1"
      firstPull.head.status shouldBe ProcessingStatus.InProgress

      val secondPull = await(repository.pullOutstandingBatch(10))
      secondPull.size shouldBe 1
      secondPull.head.item.reference shouldBe "ref2"
      secondPull.head.status shouldBe ProcessingStatus.InProgress

      val thirdPull = await(repository.pullOutstandingBatch(10))
      thirdPull shouldBe Seq.empty
    }

    "pull up stale workItems correctly" in {
      await(repository.saveRecordDetails(deEnrolmentWorkItems))
      val updatedAt = now().minus(30, ChronoUnit.MINUTES)
      val pull = await(repository.pullOutstandingBatch(5))
      await(repository.updateById(pull.head.id, 1, updatedAt))
      await(repository.updateById(pull.last.id, 1, updatedAt))

      val secondPull = await(repository.pullOutstandingBatch(5))
      secondPull.size shouldBe 2
      val workItem = secondPull.head
      workItem.status shouldBe ProcessingStatus.InProgress
      workItem.failureCount shouldBe 2
    }

    "update status to succeeded when retried 3 times" in {
      await(repository.saveRecordDetails(deEnrolmentWorkItems))
      val updatedAt = now().minus(30, ChronoUnit.MINUTES)
      val pull = await(repository.pullOutstandingBatch(5))

      eventually {
        await(repository.updateById(pull.head.id, 3, updatedAt))
        await(repository.updateById(pull.last.id, 3, updatedAt))

        val thirdPull = await(repository.pullOutstandingBatch(5))
        thirdPull.size shouldBe 0
        
        val workItem1 = await(repository.findByReference("ref1")).head
        workItem1.status shouldBe ProcessingStatus.Succeeded
        workItem1.failureCount shouldBe 3
        val workItem2 = await(repository.findByReference("ref2")).head
        workItem2.status shouldBe ProcessingStatus.Succeeded
        workItem2.failureCount shouldBe 3
      }

    }

    "return empty for non-positive pull limits" in {
      await(repository.saveRecordDetails(deEnrolmentWorkItems))

      await(repository.pullOutstandingBatch(0)) shouldBe Seq.empty
      await(repository.pullOutstandingBatch(-1)) shouldBe Seq.empty
    }

    "return WorkItems for a given file reference" in {
      // Create items with the same reference
      val itemsWithSameRef = Seq(
        DeEnrolmentWorkItem("test-ref-123", "IR-SA-UTR-1234567890,principal", java.time.Instant.now()),
        DeEnrolmentWorkItem("test-ref-123", "IR-SA-UTR-1234567892,principal", java.time.Instant.now())
      )
      await(repository.saveRecordDetails(itemsWithSameRef))

      // Create items with a different reference
      val itemsWithDifferentRef = Seq(
        DeEnrolmentWorkItem("other-ref-456", "IR-SA-UTR-9999999999,principal", java.time.Instant.now())
      )
      await(repository.saveRecordDetails(itemsWithDifferentRef))

      // Query for items with reference "test-ref-123"
      val result: Seq[WorkItem[DeEnrolmentWorkItem]] = await(repository.findByReference("test-ref-123"))

      // Verify results
      result.size shouldBe 2
      result.foreach { workItem =>
        workItem.item.reference shouldBe "test-ref-123"
        workItem.item.recordDetail.nonEmpty shouldBe true
      }

      // Verify the specific record details
      val recordDetails = result.map(_.item.recordDetail).toSet
      recordDetails.contains("IR-SA-UTR-1234567890,principal") shouldBe true
      recordDetails.contains("IR-SA-UTR-1234567892,principal") shouldBe true
    }

    "return empty sequence when no WorkItems match the reference" in {
      await(repository.saveRecordDetails(deEnrolmentWorkItems))

      val result: Seq[WorkItem[DeEnrolmentWorkItem]] = await(repository.findByReference("nonexistent-ref"))

      result shouldBe empty
    }

    "countRemainingNonCompleteByReference should return the count of incomplete work items" in {
      val refCountItems = deEnrolmentWorkItems.map(_.copy(reference = "ref-count"))
      val refOtherItems = deEnrolmentWorkItems.map(_.copy(reference = "ref-other"))

      await(repository.saveRecordDetails(refCountItems))
      await(repository.saveRecordDetails(refOtherItems))

      val count = await(repository.countRemainingNonCompleteByReference("ref-count"))
      count shouldBe 2
    }

    "countRemainingNonCompleteByReference should return 0 for non-existent reference" in {
      await(repository.saveRecordDetails(deEnrolmentWorkItems))

      val count = await(repository.countRemainingNonCompleteByReference("nonexistent-ref-count"))
      count shouldBe 0
    }

    "deleteByReference should delete all work items with given reference" in {
      val deleteRefItems = deEnrolmentWorkItems.map(_.copy(reference = "ref-delete"))
      val otherRefItems = deEnrolmentWorkItems.map(_.copy(reference = "ref-other"))

      await(repository.saveRecordDetails(deleteRefItems))
      await(repository.saveRecordDetails(otherRefItems))

      await(repository.deleteWorkItemsByReference("ref-delete"))
      val countOther = await(repository.collection.countDocuments(Filters.eq("item.reference", "ref-other")).toFuture())

      countOther shouldBe 2
    }

    "deleteByReference should not fail when reference does not exist" in {
      await(repository.saveRecordDetails(deEnrolmentWorkItems))

      await(repository.deleteWorkItemsByReference("nonexistent-ref-delete")) shouldBe()
      val count = await(repository.collection.countDocuments().toFuture())
      count shouldBe 2
    }
  }
}
