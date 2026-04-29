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

import play.api.libs.json.Json
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport

import java.time.Instant

class StatusDetailsModelSpec extends TestSupport {
  "StatusDetailsModel" should {
    val model: StatusDetailsModel = StatusDetailsModel(
      reference = "ref123",
      fileStatus = "approved",
      requestorEmail = "email@gmail.com",
      requestorPID = "pid123",
      requestorName = "Finn Hei",
      fileName = Some("file.txt"),
      creationDateTime = Some(Instant.parse("2024-01-01T00:00:00Z"))
    )

    val jsonBody = Json.parse(
      """
        |{
        |  "reference": "ref123",
        |  "fileStatus": "approved",
        |  "requestorEmail": "email@gmail.com",
        |  "requestorPID": "pid123",
        |  "requestorName": "Finn Hei",
        |  "fileName": "file.txt",
        |  "creationDateTime": "2024-01-01T00:00:00Z"
        |}
      """.stripMargin
    )

    "have correct values when writing json" in {
      Json.toJson(model) mustBe jsonBody
    }

    "have correct values when writing to  json" in {
      jsonBody.as[StatusDetailsModel] mustBe model
    }
  }

}
