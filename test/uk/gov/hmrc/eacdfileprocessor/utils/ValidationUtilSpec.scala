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
package uk.gov.hmrc.eacdfileprocessor.utils

import org.scalatest.matchers.should.Matchers.shouldBe
import uk.gov.hmrc.eacdfileprocessor.helper.TestSupport

class ValidationUtilSpec extends TestSupport {
  "isEmailValid" should {
    "return true for valid email addresse" in {
      ValidationUtil.isEmailValid("john_smith.123@hmrc.gov.uk") shouldBe true
    }
    "return false for email addresse contains spaces" in {
      ValidationUtil.isEmailValid("john  smith@hmrc.gov.uk") shouldBe false
    }
    "return true for email addresse contains special characters" in {
      ValidationUtil.isEmailValid("john!#$%&'*+-/=?^_{|}~smith@hmrc.gov.uk") shouldBe true
    }
  }
}
