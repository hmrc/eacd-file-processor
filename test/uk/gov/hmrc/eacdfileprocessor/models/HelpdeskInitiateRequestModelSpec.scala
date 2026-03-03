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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import java.time.LocalDateTime

class HelpdeskInitiateRequestModelSpec extends AnyWordSpec with Matchers {
  "HelpdeskInitiateRequestModel" should {
    "serialize and deserialize to/from JSON" in {
      val now = LocalDateTime.of(2026, 3, 5, 12, 0)
      val model = HelpdeskInitiateRequestModel(
        reference = "ref-123",
        requestorPID = "pid-456",
        requestorEmail = "test@example.com",
        requestorName = "Test User",
        creationDateTime = now
      )
      val json = Json.toJson(model)
      val fromJson = json.as[HelpdeskInitiateRequestModel]
      fromJson shouldBe model
    }

    "default fileStatus and creationDateTime" in {
      val before = LocalDateTime.now()
      val model = HelpdeskInitiateRequestModel(
        reference = "ref-123",
        requestorPID = "pid-456",
        requestorEmail = "test@example.com",
        requestorName = "Test User"
      )
      model.fileStatus shouldBe "initial"
      model.creationDateTime.isAfter(before) || model.creationDateTime.isEqual(before) shouldBe true
    }
  }
}
