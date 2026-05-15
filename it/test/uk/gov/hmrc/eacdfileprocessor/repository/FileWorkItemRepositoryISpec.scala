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

import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.await
import play.api.test.Helpers.defaultAwaitTimeout
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.FileWorkItem
import uk.gov.hmrc.mongo.workitem.ProcessingStatus

class FileWorkItemRepositoryISpec extends TestSupport with TestData {

  lazy val repository: FileWorkItemRepository = app.injector.instanceOf[FileWorkItemRepository]

  override def beforeEach(): Unit = {
    await(repository.collection.drop().headOption())
    await(repository.ensureIndexes())
  }

  "FileWorkItemRepository" should {
    "pull outstanding items and mark them in progress" in {
      val created = await(
        repository.pushNew(
          FileWorkItem(
            reference = initiateUploadDetails.reference,
            fileName = "abc.csv",
            recordDetail = "IR-SA,principal"
          )
        )
      )

      val pulled = await(repository.pullOutstandingBatch(1))

      pulled.size shouldBe 1
      pulled.head.id shouldBe created.id
      pulled.head.status shouldBe ProcessingStatus.InProgress
    }

    "mark pulled items as succeeded" in {
      await(
        repository.pushNew(
          FileWorkItem(
            reference = initiateUploadDetails.reference,
            fileName = "abc.csv",
            recordDetail = "IR-SA,principal"
          )
        )
      )

      val pulled = await(repository.pullOutstandingBatch(1)).head
      await(repository.markAsSucceeded(pulled.id)) shouldBe true

      val updated = await(repository.findById(pulled.id)).get
      updated.status shouldBe ProcessingStatus.Succeeded
    }
  }
}



