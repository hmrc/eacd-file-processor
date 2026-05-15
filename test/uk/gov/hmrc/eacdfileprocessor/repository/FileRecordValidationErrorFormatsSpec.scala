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

import org.bson.types.ObjectId
import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.libs.json.Json
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.models.{FileRecordValidationError, Reference}

import java.time.Instant

class FileRecordValidationErrorFormatsSpec extends TestSupport {

  "FileRecordValidationErrorFormats" should {

    "serialize and deserialize FileRecordValidationError" in {
      val model = FileRecordValidationError(
        id = ObjectId.get(),
        reference = Reference("ref-1"),
        fileName = "bulk.csv",
        recordDetail = "IR-SA,principal",
        errorMessage = "Invalid action type",
        creationDateTime = Instant.parse("2026-02-03T12:00:00Z")
      )

      val json = Json.toJson(model)(FileRecordValidationErrorFormats.fileRecordValidationErrorFormat)
      val back = json.as[FileRecordValidationError](FileRecordValidationErrorFormats.fileRecordValidationErrorFormat)

      back shouldBe model
    }
  }
}


