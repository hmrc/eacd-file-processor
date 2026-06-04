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

package uk.gov.hmrc.eacdfileprocessor.models

import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.models.FileStatus.APPROVED

import java.time.Instant

class FileStatusCountSpec extends TestSupport {
  val fileStatusCount: FileStatusCount = FileStatusCount(
    status = APPROVED.value,
    count = 2
  )

  val fileStatusCountJson: JsValue = Json.parse(
    """
      |{
      |  "status": "approved",
      |  "count": 2
      |}
      |""".stripMargin)

  val fileStatusCountMongoDBJson: JsValue = Json.parse(
    """
      |{
      |  "_id": "approved",
      |  "count": 2
      |}
      |""".stripMargin)

  "FileStatusCount" should {
    "deserialize to json" in {
      Json.toJson(fileStatusCount) mustBe fileStatusCountJson
    }
    "serialize to json" in {
      fileStatusCountMongoDBJson.as[FileStatusCount] mustBe fileStatusCount
    }
  }
}
