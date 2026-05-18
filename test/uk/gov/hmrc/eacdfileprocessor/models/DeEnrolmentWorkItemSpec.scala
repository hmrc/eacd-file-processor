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

import java.time.Instant

class DeEnrolmentWorkItemSpec extends TestSupport {
  val deEnrolmentWorkItem: DeEnrolmentWorkItem = DeEnrolmentWorkItem(
    reference = "ref1",
    recordDetail = "IR-SA-UTR-1234567892,delegated",
    creationDateTime = Instant.parse("2024-01-01T00:00:00Z")
  )

  val deEnrolmentWorkItemJson: JsValue = Json.parse(
    """
      |{
      |  "reference": "ref1",
      |  "recordDetail": "IR-SA-UTR-1234567892,delegated",
      |  "creationDateTime": {
      |    "$date":{
      |       "$numberLong":"1704067200000"
      |     }
      |   }
      |}
      |""".stripMargin)

  "DeEnrolmentWorkItem" should {
    "deserialize to json" in {
      Json.toJson(deEnrolmentWorkItem) mustBe deEnrolmentWorkItemJson
    }
    "serialize to json" in {
      deEnrolmentWorkItemJson.as[DeEnrolmentWorkItem] mustBe deEnrolmentWorkItem
    }
  }
}
