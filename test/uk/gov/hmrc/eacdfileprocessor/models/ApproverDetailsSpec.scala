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

class ApproverDetailsSpec extends TestSupport {
  val approverDetails: ApproverDetails = ApproverDetails(
    approverEmail = Some("approver1@hmrc.gov.uk"),
    approverPID = Some("12345678"),
    approverName = Some("Approver1"),
    errorCode = Some("errorCode1"),
    errorMessage = Some("errorMessage1")
  )

  val approverDetailsJson: JsValue = Json.parse(
    """
      |{
      |  "approverEmail": "approver1@hmrc.gov.uk",
      |  "approverPID": "12345678",
      |  "approverName": "Approver1",
      |  "errorCode": "errorCode1",
      |  "errorMessage": "errorMessage1"
      |}
      |""".stripMargin)

  "ApproverDetails" should {
    "deserialize to json" in {
      Json.toJson(approverDetails) mustBe approverDetailsJson
    }
    "serialize to json" in {
      approverDetailsJson.as[ApproverDetails] mustBe approverDetails
    }
  }
}
