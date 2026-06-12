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
import org.bson.types.ObjectId
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.test.Helpers.{await, defaultAwaitTimeout}
import uk.gov.hmrc.eacdfileprocessor.models.{FileRecordValidationError, Reference}

class FileRecordValidationErrorRepositoryISpec extends IntegrationSpec {

  lazy val repository: FileRecordValidationErrorRepository = app.injector.instanceOf[FileRecordValidationErrorRepository]

  override def beforeEach(): Unit = {
    await(repository.collection.drop().headOption())
    await(repository.ensureIndexes())
  }

  "FileRecordValidationErrorRepository" should {

    "persist validation errors" in {
      val error = FileRecordValidationError(
        id = ObjectId.get(),
        reference = Reference("ref-err-1"),
        fileName = "bulk.csv",
        recordDetail = "IR-SA,invalid",
        errorMessage = "Invalid action"
      )

      await(repository.create(error))

      val stored = await(repository.collection.find().headOption())
      stored.value.reference shouldBe Reference("ref-err-1")
      stored.value.fileName shouldBe "bulk.csv"
      stored.value.errorMessage shouldBe "Invalid action"
    }
  }
}

