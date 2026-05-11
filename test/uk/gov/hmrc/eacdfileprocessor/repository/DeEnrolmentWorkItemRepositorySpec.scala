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

import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.libs.json.JsError
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.config.AppConfig
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.UploadedDetails
import uk.gov.hmrc.mongo.MongoComponent

class DeEnrolmentWorkItemRepositorySpec extends TestSupport with TestData:
  private val mockMongoComponent: MongoComponent = app.injector.instanceOf[MongoComponent]
  private val mockAppConfig = mock[AppConfig]
  when(mockAppConfig.workItemTimeToLive).thenReturn("270")
  when(mockAppConfig.retryInProgressAfter).thenReturn(30)

  private val repository = new DeEnrolmentWorkItemMongoRepository(mockMongoComponent, mockAppConfig)

  "DeEnrolmentWorkItemMongoRepository" should {
    "save record details" in {
      val actual = await(repository.saveRecordDetails(deEnrolmentWorkItems, "ref1"))
      actual.size shouldBe 2
    }
  }