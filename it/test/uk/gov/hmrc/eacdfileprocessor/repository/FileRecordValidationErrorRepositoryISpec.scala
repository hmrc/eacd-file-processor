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

    "countByReference should return count of validation errors for a reference" in {
      val reference = Reference("ref-count-test")
      val errors = Seq(
        FileRecordValidationError(
          id = ObjectId.get(),
          reference = reference,
          fileName = "bulk.csv",
          recordDetail = "IR-SA-UTR-1234567890,principal",
          errorMessage = "Invalid UTR format"
        ),
        FileRecordValidationError(
          id = ObjectId.get(),
          reference = reference,
          fileName = "bulk.csv",
          recordDetail = "IR-SA-UTR-0987654321,principal",
          errorMessage = "UTR not found"
        ),
        FileRecordValidationError(
          id = ObjectId.get(),
          reference = Reference("other-ref"),
          fileName = "bulk.csv",
          recordDetail = "IR-SA-UTR-9999999999,principal",
          errorMessage = "Invalid format"
        )
      )

      errors.foreach(error => await(repository.create(error)))

      val count = await(repository.countByReference(reference))
      count shouldBe 2
    }

    "countByReference should return 0 when reference has no validation errors" in {
      val reference = Reference("ref-no-errors")
      val otherReference = Reference("ref-with-errors")

      val error = FileRecordValidationError(
        id = ObjectId.get(),
        reference = otherReference,
        fileName = "bulk.csv",
        recordDetail = "IR-SA-UTR-1234567890,principal",
        errorMessage = "Invalid format"
      )

      await(repository.create(error))

      val count = await(repository.countByReference(reference))
      count shouldBe 0
    }

    "countByReference should return 0 for non-existent reference" in {
      val count = await(repository.countByReference(Reference("nonexistent-ref")))
      count shouldBe 0
    }
  }
}

