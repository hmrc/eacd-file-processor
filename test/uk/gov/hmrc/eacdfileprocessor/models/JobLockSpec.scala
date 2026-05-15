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

import org.scalatest.matchers.should.Matchers.shouldBe
import play.api.libs.json.Json
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport

import java.time.Instant

class JobLockSpec extends TestSupport {

  "JobLock" should {

    "serialize and deserialize using mongo instant format" in {
      val model = JobLock("FileWorkItemPullJob", Instant.parse("2026-01-01T00:00:00Z"))
      val format = summon[play.api.libs.json.Format[JobLock]]

      val json = Json.toJson(model)(format)
      val back = json.as[JobLock](format)

      back shouldBe model
    }
  }
}


