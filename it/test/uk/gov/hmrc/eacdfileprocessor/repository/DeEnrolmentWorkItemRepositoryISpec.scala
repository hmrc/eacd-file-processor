/*
 * Copyright 2024 HM Revenue & Customs
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

import org.mockito.Mockito.*
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.DeEnrolmentWorkItem
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.workitem.ProcessingStatus.ToDo
import uk.gov.hmrc.mongo.workitem.WorkItem

import java.time.Instant
import scala.language.postfixOps

class DeEnrolmentWorkItemRepositoryISpec extends TestSupport with TestData {
  private val mockAppConfig = mock[AppConfig]
  when(mockAppConfig.workItemTimeToLive).thenReturn("270")
  when(mockAppConfig.retryInProgressAfter).thenReturn(30)

  private val mongoRepository: MongoComponent = app.injector.instanceOf[MongoComponent]
  private val repository = new DeEnrolmentWorkItemMongoRepository(mongoRepository, mockAppConfig)

  override def beforeEach(): Unit = {
    await(repository.collection.deleteMany(Filters.exists("_id")).toFuture())
  }

  private def collectionSize: Long = {
    await(repository.collection.estimatedDocumentCount().toFuture())
  }

  "repository" should {
    "update status of an item of work to Succeeded when successfully completed" in {
      val result: Seq[WorkItem[DeEnrolmentWorkItem]] = await(repository.saveRecordDetails(deEnrolmentWorkItems, "ref1"))
      result.size shouldBe 2
      result(0).status shouldBe ToDo
      result(1).status shouldBe ToDo
    }
  }
}
