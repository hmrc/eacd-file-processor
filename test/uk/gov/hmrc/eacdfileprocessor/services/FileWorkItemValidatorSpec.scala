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

package uk.gov.hmrc.eacdfileprocessor.services

import org.scalatest.matchers.should.Matchers.shouldBe
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport
import uk.gov.hmrc.eacdfileprocessor.utils.FileWorkItemValidator

class FileWorkItemValidatorSpec extends TestSupport {

  private val validator = new FileWorkItemValidator

  "FileWorkItemValidator" should {
    "return row structure invalid when there are not exactly 2 columns" in {
      validator.validate("IR-SA~UTR~1234567890,principal,extra", Set.empty) shouldBe Some("Row structure invalid")
      validator.validate("IR-SA~UTR~1234567890", Set.empty) shouldBe Some("Row structure invalid")
    }

    "return agent principal validation error for agent services when action type is not agent" in {
      validator.validate("HMRC-MTD-IT~MTDBSA~1234567890,principal", Set("HMRC-MTD-IT")) shouldBe Some("Agent principal deallocation must specify 'agent'")
    }

    "return invalid action type when non-agent service specifies agent action" in {
      validator.validate("IR-SA~UTR~1234567890,agent", Set("HMRC-MTD-IT")) shouldBe Some("Invalid action type")
    }

    "return invalid action type when action is not in supported values" in {
      validator.validate("IR-SA~UTR~1234567890,principla", Set.empty) shouldBe Some("Invalid action type")
    }

    "return no error when record is valid" in {
      validator.validate("IR-SA~UTR~1234567890,principal", Set.empty) shouldBe None
      validator.validate("HMRC-MTD-IT~MTDBSA~1234567890,agent", Set("HMRC-MTD-IT")) shouldBe None
    }

    "derive the service key from the enrolment key prefix before the first tilde" in {
      validator.validate("IR-SA~UTR~1234567890,principal", Set("IR-SA")) shouldBe Some("Agent principal deallocation must specify 'agent'")
    }
  }
}

