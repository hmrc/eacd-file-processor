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
import play.api.libs.json.JsError
import uk.gov.hmrc.eacdfileprocessor.helper.{TestData, TestSupport}
import uk.gov.hmrc.eacdfileprocessor.models.UploadedDetails

class FileRepositorySpec extends TestSupport with TestData:
  "Serialization and deserialization of UploadedDetails" should {

    "serialize and deserialize scanned status" in {
      val serialized = FileUploadRepoFormat.mongoFormat.writes(scannedUploadedDetails)
      val output = FileUploadRepoFormat.mongoFormat.reads(serialized)

      output.get mustBe scannedUploadedDetails
    }

    "serialize and deserialize failed status" in {
      val serialized = FileUploadRepoFormat.mongoFormat.writes(failedUploadedDetails)
      val output = FileUploadRepoFormat.mongoFormat.reads(serialized)

      output.get mustBe failedUploadedDetails
    }

    "deserialize either scanned nor failed status" in {
      val output = FileUploadRepoFormat.mongoFormat.reads(missingFieldUploadedDetails)
      output.isError shouldBe true
      output.asInstanceOf[JsError].errors.head._2.head.message shouldBe "Missing failureReason or name fields"
    }
  }