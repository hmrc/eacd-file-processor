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

package uk.gov.hmrc.eacdfileprocessor.support.controllers

import helper.IntegrationSpec
import org.mongodb.scala.SingleObservableFuture
import org.mongodb.scala.model.Filters
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.http.Status.{NO_CONTENT, OK}
import play.api.libs.json.Json
import play.api.test.Helpers.{GET, await, contentAsJson, route, status, writeableOf_AnyContentAsEmpty}
import play.api.test.{DefaultAwaitTimeout, FakeRequest}
import uk.gov.hmrc.eacdfileprocessor.controllers.routes
import uk.gov.hmrc.eacdfileprocessor.helper.TestData
import uk.gov.hmrc.eacdfileprocessor.models.DeEnrolmentWorkItem
import uk.gov.hmrc.eacdfileprocessor.repository.DeEnrolmentWorkItemMongoRepository

import java.time.Instant

class WorkItemControllerISpec extends IntegrationSpec with TestData with DefaultAwaitTimeout {

  lazy val deEnrolmentWorkItemRepository: DeEnrolmentWorkItemMongoRepository =
    app.injector.instanceOf[DeEnrolmentWorkItemMongoRepository]

  override def beforeEach(): Unit = {
    await(deEnrolmentWorkItemRepository.collection.deleteMany(Filters.exists("_id")).toFuture())
    await(deEnrolmentWorkItemRepository.ensureIndexes())
  }

  "GET /work-item/:reference (integration)" should {
    "return 200 with the matching work items for a reference" in {
      val creationDateTime = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)

      val reference = "work-item-ref-123"
      val items = Seq(
        DeEnrolmentWorkItem(reference, "IR-SA-UTR-1234567890,principal", creationDateTime),
        DeEnrolmentWorkItem(reference, "IR-SA-UTR-1234567892,principal", creationDateTime)
      )

      await(deEnrolmentWorkItemRepository.saveRecordDetails(items))

      val request = FakeRequest(GET, s"/eacd-file-processor/support-tool/work-item/$reference")
        .withHeaders("Authorization" -> "Bearer test-token")

      val result = route(app, request).get

      status(result) shouldBe OK
      contentAsJson(result).as[Seq[DeEnrolmentWorkItem]] shouldBe items
    }

    "return 204 when no work items exist for the reference" in {
      val reference = "missing-reference"
      val request = FakeRequest(GET, s"/eacd-file-processor/support-tool/work-item/$reference")
        .withHeaders("Authorization" -> "Bearer test-token")

      val result = route(app, request).get

      status(result) shouldBe NO_CONTENT
    }
  }
}

